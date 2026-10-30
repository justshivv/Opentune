import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { getAudio, usePlayer } from '@/state/player';
import { isListen, primaryArtist, signature } from './lastfmRules';
import { trackLog } from './trackLog';

/**
 * Last.fm scrobbling, as the Android app does it (core/…/lastfm/LastFm.kt):
 * "now playing" when a song starts, a scrobble once half of it (or four
 * minutes) has been heard, kept in a queue until Last.fm takes it.
 *
 * Sign-in is Last.fm's own: OpenTune asks for a token, the listener approves
 * it on last.fm in a new tab, and the page trades it for a session key. No
 * password passes through OpenTune. Last.fm answers web pages directly (CORS),
 * so nothing goes through the extension.
 */

const API = 'https://ws.audioscrobbler.com/2.0/';
const AUTH_PAGE = 'https://www.last.fm/api/auth/';
const MAX_QUEUED = 500;
const BATCH = 50;
const APPROVAL_POLL_MS = 3_000;
const APPROVAL_TIMEOUT_MS = 5 * 60_000;

/** OpenTune's Last.fm application, from the build (pages.yml); a listener can use their own instead. */
const BUILD_KEY = import.meta.env.VITE_LASTFM_API_KEY ?? '';
const BUILD_SECRET = import.meta.env.VITE_LASTFM_API_SECRET ?? '';

export interface Scrobble {
  artist: string;
  track: string;
  album: string | null;
  /** Unix seconds when the listen started. */
  timestamp: number;
  durationSec: number | null;
}

interface LastFmState {
  /** The session, with the key pair it was made with: a later change of keys doesn't break it. */
  session: { key: string; user: string; apiKey: string; secret: string } | null;
  /** The listener's own application, used when the build carries none. */
  ownKey: string;
  ownSecret: string;
  scrobbling: boolean;
  queue: Scrobble[];
  setOwn: (key: string, secret: string) => void;
  setScrobbling: (on: boolean) => void;
  signOut: () => void;
}

export const useLastFm = create<LastFmState>()(
  persist(
    (set) => ({
      session: null,
      ownKey: '',
      ownSecret: '',
      scrobbling: true,
      queue: [],
      setOwn: (ownKey, ownSecret) => set({ ownKey: ownKey.trim(), ownSecret: ownSecret.trim() }),
      setScrobbling: (scrobbling) => set({ scrobbling }),
      signOut: () => set({ session: null, queue: [] }),
    }),
    { name: 'opentune:lastfm', version: 1 },
  ),
);

/** The key pair a new sign-in uses: the build's, else the listener's own. */
export function loginKeys(): { apiKey: string; secret: string } | null {
  if (BUILD_KEY && BUILD_SECRET) return { apiKey: BUILD_KEY, secret: BUILD_SECRET };
  const { ownKey, ownSecret } = useLastFm.getState();
  return ownKey && ownSecret ? { apiKey: ownKey, secret: ownSecret } : null;
}

export const buildHasKeys = () => !!(BUILD_KEY && BUILD_SECRET);

class LastFmError extends Error {
  constructor(readonly code: number, message: string) {
    super(message);
  }
}

async function call(params: Record<string, string>, keys: { apiKey: string; secret: string }, sk?: string): Promise<Record<string, unknown>> {
  const all: Record<string, string> = { ...params, api_key: keys.apiKey, ...(sk && { sk }) };
  const body = new URLSearchParams({ ...all, api_sig: signature(all, keys.secret), format: 'json' });
  const response = await fetch(API, { method: 'POST', body });
  const json = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  if (!json) throw new Error(`Last.fm answered ${response.status}`);
  if (json.error) throw new LastFmError(Number(json.error), String(json.message ?? 'Last.fm refused the request'));
  return json;
}

// ---- Sign-in -------------------------------------------------------------------

let approval: AbortController | null = null;

/**
 * Opens Last.fm's approval page and waits for the listener to allow OpenTune
 * there. Resolves with the account name; rejects if they don't within five
 * minutes, or [cancelSignIn] is called.
 */
export async function signIn(): Promise<string> {
  const keys = loginKeys();
  if (!keys) throw new Error('Add a Last.fm API key and secret first.');
  cancelSignIn();
  const controller = new AbortController();
  approval = controller;
  // The tab opens on the click, before the token comes back, or the browser blocks it as a popup.
  const tab = window.open('about:blank', '_blank');
  try {
    const token = String(((await call({ method: 'auth.getToken' }, keys)) as { token?: string }).token ?? '');
    if (!token) throw new Error('Last.fm didn’t give a sign-in token');
    const url = `${AUTH_PAGE}?api_key=${encodeURIComponent(keys.apiKey)}&token=${encodeURIComponent(token)}`;
    if (tab) tab.location.href = url;
    else window.open(url, '_blank');
    const until = Date.now() + APPROVAL_TIMEOUT_MS;
    while (Date.now() < until) {
      await new Promise((resolve) => setTimeout(resolve, APPROVAL_POLL_MS));
      if (controller.signal.aborted) throw new Error('Sign-in cancelled');
      try {
        const answer = (await call({ method: 'auth.getSession', token }, keys)) as { session?: { key?: string; name?: string } };
        const key = answer.session?.key;
        if (!key) continue;
        const user = answer.session?.name ?? 'your account';
        useLastFm.setState({ session: { key, user, ...keys } });
        flush();
        return user;
      } catch (error) {
        // 14: not approved yet. Anything else ends the wait.
        if (!(error instanceof LastFmError) || error.code !== 14) throw error;
      }
    }
    throw new Error('Last.fm sign-in timed out. Try again and press “Yes, allow access” on last.fm.');
  } catch (error) {
    if (tab && !tab.closed && tab.location.href === 'about:blank') tab.close();
    throw error;
  } finally {
    if (approval === controller) approval = null;
  }
}

export function cancelSignIn(): void {
  approval?.abort();
  approval = null;
}

// ---- Scrobbling -----------------------------------------------------------------

let flushing = false;

/** Sends what's waiting, 50 at a time; what Last.fm doesn't take stays for the next try. */
export function flush(): void {
  const { session } = useLastFm.getState();
  if (!session || flushing) return;
  flushing = true;
  void (async () => {
    try {
      for (;;) {
        const batch = useLastFm.getState().queue.slice(0, BATCH);
        if (batch.length === 0) break;
        const params: Record<string, string> = { method: 'track.scrobble' };
        batch.forEach((s, i) => {
          params[`artist[${i}]`] = s.artist;
          params[`track[${i}]`] = s.track;
          params[`timestamp[${i}]`] = String(s.timestamp);
          if (s.album) params[`album[${i}]`] = s.album;
          if (s.durationSec) params[`duration[${i}]`] = String(s.durationSec);
        });
        try {
          await call(params, session, session.key);
        } catch (error) {
          // 9: the session was revoked on last.fm. Signed out; the queue goes with it.
          if (error instanceof LastFmError && error.code === 9) useLastFm.getState().signOut();
          else trackLog.w(`lastfm: ${batch.length} scrobbles kept for later: ${String(error)}`);
          break;
        }
        useLastFm.setState((s) => ({ queue: s.queue.slice(batch.length) }));
      }
    } finally {
      flushing = false;
    }
  })();
}

function enqueue(scrobble: Scrobble): void {
  useLastFm.setState((s) => ({ queue: [...s.queue, scrobble].slice(-MAX_QUEUED) }));
  flush();
}

function nowPlaying(s: Omit<Scrobble, 'timestamp'>): void {
  const { session } = useLastFm.getState();
  if (!session) return;
  const params: Record<string, string> = { method: 'track.updateNowPlaying', artist: s.artist, track: s.track };
  if (s.album) params.album = s.album;
  if (s.durationSec) params.duration = String(s.durationSec);
  call(params, session, session.key).catch((error: unknown) => trackLog.w(`lastfm: now playing failed: ${String(error)}`));
}

/**
 * Follows the player: counts the seconds of each song actually heard (a seek
 * forward doesn't count), sends "now playing" once it's audible and queues the
 * scrobble when it qualifies. One scrobble per play; a repeat is a new play.
 * Returns the stop.
 */
export function startScrobbling(): () => void {
  let current: { id: string; meta: Omit<Scrobble, 'timestamp'>; startedAt: number; heard: number; announced: boolean; done: boolean } | null = null;
  let last = 0;

  const tick = () => {
    const { session, scrobbling } = useLastFm.getState();
    const player = usePlayer.getState();
    const playing = player.nowPlaying;
    const audio = getAudio();
    if (!session || !scrobbling || !playing || !audio) { current = null; return; }
    const position = audio.currentTime;
    // A new song, or the same one started over (repeat): a new play.
    if (!current || current.id !== playing.videoId || (position + 2 < last && position < 3)) {
      const entry = player.queue[player.index]?.track;
      const artist = primaryArtist(playing.artist);
      current = artist
        ? {
            id: playing.videoId,
            meta: { artist, track: playing.title, album: entry?.videoId === playing.videoId ? (entry.album?.name ?? null) : null, durationSec: Math.round(playing.durationSec) || null },
            startedAt: Math.floor(Date.now() / 1000),
            heard: 0,
            announced: false,
            done: false,
          }
        : null;
      last = position;
      if (!current) return;
    }
    if (player.status === 'playing') {
      const step = position - last;
      // Ordinary playback moves about a second per tick; a jump forward is a seek, not listening.
      if (step > 0 && step < 3) current.heard += step;
      if (!current.announced) { current.announced = true; nowPlaying(current.meta); }
      if (!current.done && isListen(current.heard, playing.durationSec)) {
        current.done = true;
        enqueue({ ...current.meta, timestamp: current.startedAt });
      }
    }
    last = position;
  };

  const timer = setInterval(tick, 1_000);
  const online = () => flush();
  window.addEventListener('online', online);
  flush();
  return () => {
    clearInterval(timer);
    window.removeEventListener('online', online);
  };
}
