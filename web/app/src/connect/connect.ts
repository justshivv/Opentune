import { create } from 'zustand';
import { toast } from '@/components/toastStore';
import type { Track } from '@/data/music/types';
import { getAudio, loadingTrack, partyPlayer, refreshMediaSession, setConnectHooks, usePlayer, type RemoteMedia } from '@/state/player';
import { toRoomTrack } from '@/together/room';
import { qualityLabel } from '@/shell/quality';
import { ConnectGroup } from './group';
import type { CTrack } from './messages';
import { ConnectSession, controllingOf, meant, positionAt, type ConnectPlayer, type ConnectView, type Device, type Snapshot } from './session';

/**
 * Connect on the web: this browser is one of the person's devices, sees what
 * the others play, controls it and hands playback between them. The same
 * group and messages as the Android app's Connect.
 */

const STORE_KEY = 'opentune:connect';
const MAX_FORMER = 3;
const SOURCE = { title: 'From your devices', type: 'queue' as const };

interface Saved {
  secret: string | null;
  id: string;
  name: string;
  enabled: boolean;
  /** May the key be kept in the YouTube account? null until asked, once, after signing in. */
  accountSync: boolean | null;
  /** Groups this browser was in before, listened to for their devices that missed a merge. */
  former: string[];
}

function defaultName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /Android/.test(ua) ? 'Android' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

function load(): Saved {
  let saved: Partial<Saved> & { consent?: boolean | null } = {};
  try {
    saved = JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}') as Partial<Saved>;
  } catch {
    // A blocked or broken store starts fresh.
  }
  return {
    secret: saved.secret ?? null,
    id: saved.id ?? crypto.randomUUID().replace(/-/g, '').slice(0, 12),
    name: saved.name ?? defaultName(),
    enabled: saved.enabled ?? true,
    // Asked for, never assumed (`accountSync` was a default-on switch before).
    accountSync: saved.consent ?? null,
    former: saved.former ?? [],
  };
}

function save(s: Saved): void {
  try {
    const { accountSync, ...rest } = s;
    localStorage.setItem(STORE_KEY, JSON.stringify({ ...rest, consent: accountSync }));
  } catch {
    // Kept for this visit only.
  }
}

export interface ConnectNotice {
  text: string;
  at: number;
}

export const useConnect = create<{
  saved: Saved;
  /** The group's link, for showing and sharing; null with no group yet. */
  link: { app: string; web: string } | null;
  view: ConnectView | null;
  notice: ConnectNotice | null;
  /**
   * True once the account has been looked in and holds no group yet: only
   * then is this browser asked about account sync. An account that already
   * holds one said yes on another device, so this browser joins without asking.
   */
  askAccount: boolean;
}>(() => ({ saved: load(), link: null, view: null, notice: null, askAccount: false }));


/** The other device this browser shows and controls, if any. */
export const useControlling = (): Device | null => useConnect((s) => controllingOf(s.view));

// ---- Tracks across the boundary -----------------------------------------------------------

const toCTrack = (t: Track | undefined | null): CTrack | null => toRoomTrack(t);

function fromCTrack(t: CTrack): Track {
  const parts = t.length?.split(':').map(Number) ?? [];
  const seconds = parts.length && parts.every(Number.isFinite) ? parts.reduce((a, b) => a * 60 + b, 0) : null;
  return {
    videoId: t.id,
    title: t.title,
    artists: t.artist ? [{ name: t.artist, id: null }] : [],
    album: t.album ? { name: t.album, id: null } : null,
    durationSec: seconds,
    explicit: false,
    thumbnail: t.thumb ?? null,
    index: null,
    plays: null,
  };
}

// ---- This browser's player, as Connect sees it ---------------------------------------------

/** True while a command from another device is carried out here: the player's buttons then act here. */
let applying = false;
function here(f: () => void): void {
  applying = true;
  try {
    f();
  } finally {
    applying = false;
  }
}

/** "3:45" or "1:02:03" in seconds; null when it isn't one. */
function lengthSec(text: string): number | null {
  const parts = text.split(':').map(Number);
  if (!parts.length || parts.some((n) => !Number.isFinite(n))) return null;
  return parts.reduce((a, b) => a * 60 + b, 0);
}

/** The queue row of entry [at] in Snapshot.queue (the shareable songs after this one), checked against [id]. */
/** The player's rows behind Snapshot.queue: what comes after the song playing, as other devices see it. */
function queuedRows(): number[] {
  const { queue, index } = usePlayer.getState();
  const rows: number[] = [];
  for (let i = index + 1; i < queue.length; i++) if (toCTrack(queue[i]!.track)) rows.push(i);
  return rows;
}

function queuedRow(at: number, id: string): number | null {
  const { queue } = usePlayer.getState();
  const rows = queuedRows();
  const exact = rows[at];
  if (exact !== undefined && queue[exact]!.track.videoId === id) return exact;
  return rows.find((r) => queue[r]!.track.videoId === id) ?? null;
}

export const webPlayer: ConnectPlayer = {
  snapshot(): Snapshot | null {
    const { queue, index, nowPlaying, volume, shuffle, repeat, status, autoplay } = usePlayer.getState();
    if (!nowPlaying) return null;
    const entry = queue[index];
    const track = toCTrack(entry?.track);
    const audio = getAudio();
    // A song loading (a skip, a handover): the audio element still holds the last song, its
    // time and its play state, which went out as the new song's. The others showed the old
    // song's place running on under the new one's name until it started; and a handover's
    // song, loading, read as paused, so the device that handed it over kept the music.
    const starting = loadingTrack();
    return {
      track,
      private: !track,
      // Moving, not just meant to: other devices run this one's clock on from its last report
      // while it says it's playing, so a stall said as playing ran their timestamp ahead of it.
      playing: !starting && !!audio && !audio.paused && !audio.seeking && audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA,
      wants: starting ? starting.plays : !!audio && !audio.paused,
      pos: starting ? starting.startSec * 1000 : Math.max(0, (audio?.currentTime ?? 0) * 1000),
      queue: queue.slice(index + 1).map((e) => toCTrack(e.track)).filter((t): t is CTrack => !!t),
      volume,
      shuffle,
      repeat,
      autoplay,
      quality: nowPlaying.streamInfo ? qualityLabel(nowPlaying.streamInfo, false, status).text : null,
    };
  },
  play: () => {
    if (getAudio()) partyPlayer.play();
    else here(() => usePlayer.getState().togglePlay());
  },
  pause: () => partyPlayer.pause(),
  next: () => here(() => usePlayer.getState().next()),
  previous: () => here(() => usePlayer.getState().previous()),
  seekTo: (ms) => partyPlayer.seekTo(ms / 1000),
  setVolume: (v) => usePlayer.getState().setVolume(v),
  setShuffle: (on) => here(() => {
    if (usePlayer.getState().shuffle !== on) usePlayer.getState().toggleShuffle();
  }),
  // No smart shuffle here: plain shuffle stands in for it.
  setAutoplay: (on) => here(() => usePlayer.getState().setAutoplay(on)),
  setRepeat: (mode) => here(() => {
    // Repeat only cycles here; at most three steps reach any mode.
    for (let i = 0; i < 3 && usePlayer.getState().repeat !== mode; i++) usePlayer.getState().cycleRepeat();
  }),
  playTrack: (t) => here(() => {
    usePlayer.getState().playNext([fromCTrack(t)]);
    usePlayer.getState().next();
  }),
  addToQueue: (ts) => here(() => usePlayer.getState().addToQueue(ts.map(fromCTrack))),
  playNext: (ts) => here(() => usePlayer.getState().playNext(ts.map(fromCTrack))),
  playQueued: (at, id) => {
    const row = queuedRow(at, id);
    if (row === null) return false;
    usePlayer.getState().jumpTo(row);
    return true;
  },
  removeQueued: (at, id) => {
    const row = queuedRow(at, id);
    if (row === null) return false;
    usePlayer.getState().removeAt(row);
    return true;
  },
  moveQueued: (from, to, id) => {
    const row = queuedRow(from, id);
    const target = queuedRows()[to];
    if (row === null || target === undefined) return false;
    if (row !== target) usePlayer.getState().move(row, target);
    return true;
  },
  // A tab nobody has clicked in since it loaded may not start sound (the browser's autoplay rule).
  needsTap: () => navigator.userActivation ? !navigator.userActivation.hasBeenActive : false,
  load: (queue, index, pos, playing) => {
    if (!queue.length || index < 0 || index >= queue.length) return false;
    void partyPlayer.load(queue.map(fromCTrack), queue.map(() => 'user'), index, pos / 1000, playing, SOURCE);
    return true;
  },
};

// ---- Running -------------------------------------------------------------------------------

let session: ConnectSession | null = null;
let unsubscribe: (() => void) | null = null;
let settle: ReturnType<typeof setTimeout> | null = null;
/** The audio element whose stalls are being listened to; the player swaps between two for crossfades. */
let heard: HTMLMediaElement | null = null;
const MEDIA_EVENTS = ['waiting', 'stalled', 'playing', 'seeked', 'canplay'] as const;
const onMedia = () => {
  if (settle) clearTimeout(settle);
  settle = setTimeout(() => session?.localChanged(), 150);
};
/** Listens to the audio element now playing: a stall starting or ending goes out as this device's state. */
function hearAudio(): void {
  const audio = getAudio() ?? null;
  if (audio === heard) return;
  for (const e of MEDIA_EVENTS) heard?.removeEventListener(e, onMedia);
  heard = audio;
  for (const e of MEDIA_EVENTS) audio?.addEventListener(e, onMedia);
}
let starting: Promise<void> = Promise.resolve();

const notice = (template: string, name: string) => useConnect.setState({ notice: { text: template.replace('{0}', name), at: Date.now() } });

/**
 * When this browser last took the music, kept across sessions: Connect
 * starting over (a group joined, the switch flipped) isn't taking it.
 * Counted as taking it, a paused browser became the device shown everywhere.
 */
let took = 0;

function stop(): void {
  if (session) took = session.tookAt;
  session?.stop();
  session = null;
  unsubscribe?.();
  unsubscribe = null;
  for (const e of MEDIA_EVENTS) heard?.removeEventListener(e, onMedia);
  heard = null;
  useConnect.setState({ view: null });
}

/** (Re)starts Connect from what's saved: on load, and after any change to the group or the switch. */
function restart(): void {
  starting = starting.then(async () => {
    stop();
    const saved = useConnect.getState().saved;
    if (!saved.secret) return useConnect.setState({ link: null });
    const group = await ConnectGroup.fromText(saved.secret);
    if (!group) return;
    const former = (await Promise.all(saved.former.map((f) => ConnectGroup.fromText(f)))).filter((g): g is ConnectGroup => !!g && g.encoded !== group.encoded);
    useConnect.setState({ link: { app: group.appLink, web: group.webLink } });
    if (!saved.enabled) return;
    const s = new ConnectSession(group, saved.id, saved.name, 'web', webPlayer, (view) => {
      if (session === s) useConnect.setState({ view });
    }, notice, undefined, undefined, undefined, (secret) => joinGroup(secret, false), former, took);
    session = s;
    s.start();
    // The player changing (a song, play/pause, the queue) goes out a moment after it settles.
    hearAudio();
    unsubscribe = usePlayer.subscribe((now, before) => {
      hearAudio();
      if (
        now.nowPlaying?.videoId === before.nowPlaying?.videoId && now.nowPlaying?.streamInfo === before.nowPlaying?.streamInfo &&
        now.status === before.status && now.queue === before.queue && now.volume === before.volume &&
        now.shuffle === before.shuffle && now.repeat === before.repeat && now.autoplay === before.autoplay
      ) return;
      if (settle) clearTimeout(settle);
      settle = setTimeout(() => session?.localChanged(), 300);
    });
  });
}

function update(part: Partial<Saved>): void {
  const saved = { ...useConnect.getState().saved, ...part };
  save(saved);
  useConnect.setState({ saved });
}

let started = false;
/** Starts Connect once, when the app shell mounts. */
export function initConnect(): void {
  if (started) return;
  started = true;
  update({});
  setConnectHooks({
    intercept: (control) => {
      const s = session;
      const target = controllingOf(useConnect.getState().view);
      if (!s || !target || applying) return false;
      switch (control.kind) {
        // By what it's meant to do: one stalled to load is playing, and Pause pauses it.
        case 'toggle': return s.intercept(meant(target.state) ? 'pause' : 'play');
        case 'next': return s.intercept('next');
        case 'previous': return s.intercept('previous');
        case 'seek': return s.intercept('seek', Math.round(control.ms));
        case 'shuffle': return s.intercept('shuffle', 0, target.state?.shuffle ? 0 : 1);
        case 'repeat': {
          const next = target.state?.repeat === 'off' ? 'all' : target.state?.repeat === 'all' ? 'one' : 'off';
          return s.intercept('repeat', 0, 0, next);
        }
      }
    },
    // The system's media controls show and act on the device playing elsewhere.
    remote: (): RemoteMedia | null => {
      const d = controllingOf(useConnect.getState().view);
      const t = d?.state?.track;
      if (!d || !t) return null;
      return {
        title: t.title,
        artist: t.artist ?? '',
        artworkUrl: t.thumb ?? null,
        playing: !!d.state?.playing,
        posSec: positionAt(d, Date.now()) / 1000,
        durationSec: t.length ? lengthSec(t.length) : null,
      };
    },
    // Music started, or queued, here while another device plays goes there.
    play: (tracks, startIndex, shuffle) => {
      const s = session;
      const target = controllingOf(useConnect.getState().view);
      if (!s || !target || applying) return false;
      const start = toCTrack(tracks[startIndex]);
      if (!start) return false;
      const before = tracks.slice(0, startIndex).map(toCTrack).filter((t): t is CTrack => !!t);
      const after = tracks.slice(startIndex + 1).map(toCTrack).filter((t): t is CTrack => !!t);
      if (shuffle) {
        const rest = [...before, ...after];
        for (let i = rest.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [rest[i], rest[j]] = [rest[j]!, rest[i]!];
        }
        s.playOn(target.id, [start, ...rest], 0);
      } else s.playOn(target.id, [...before, start, ...after], before.length);
      return true;
    },
    queue: (tracks, next) => {
      const s = session;
      const target = controllingOf(useConnect.getState().view);
      if (!s || !target || applying) return false;
      const shared = tracks.map(toCTrack).filter((t): t is CTrack => !!t);
      if (!shared.length) return false;
      if (next) s.playNextOn(target.id, shared);
      else s.addToQueueOn(target.id, shared);
      return true;
    },
    // Seeks and play/pause pressed here don't change the store's song or queue.
    onIntent: () => {
      if (settle) clearTimeout(settle);
      settle = setTimeout(() => session?.localChanged(), 150);
    },
  });
  addEventListener('pagehide', () => session?.stop());
  // Back from the browser's back-forward cache: the session stopped on the way out, and stayed stopped.
  addEventListener('pageshow', (e: PageTransitionEvent) => {
    if (e.persisted) restart();
  });

  // The system's media controls follow the device playing elsewhere, and come back here after.
  let shownKey = '';
  useConnect.subscribe((s) => {
    const d = controllingOf(s.view);
    const st = d?.state;
    const key = d && st?.track ? `${d.id}|${st.track.id}|${st.playing}|${Math.round((st.pos ?? 0) / 1000)}` : '';
    if (key === shownKey) return;
    shownKey = key;
    refreshMediaSession();
  });
  restart();
  void syncAccount();
}

export function setEnabled(on: boolean): void {
  update({ enabled: on });
  restart();
}

export function setDeviceName(name: string): void {
  const clean = name.trim().slice(0, 40);
  if (!clean) return;
  update({ name: clean });
  session?.rename(clean);
}

export function setAccountSync(on: boolean): void {
  useConnect.setState({ askAccount: false });
  update({ accountSync: on });
  if (on) void syncAccount();
}

/**
 * With account sync on: joins the group kept in the YouTube account, or keeps
 * this browser's group there when the account has none yet. [push] puts this
 * browser's group there regardless (a new secret after "Disconnect all").
 * Signed out or without the extension, it quietly does nothing.
 */
let looking = false;

export async function syncAccount(push = false): Promise<void> {
  const answer = useConnect.getState().saved.accountSync;
  if (answer === false) return;
  if (answer === null) {
    await lookInAccount();
    return;
  }
  try {
    // YouTube.js comes with it; loaded only when the account is used.
    const { readSecret, write: writeToAccount } = await import('./accountSync');
    const kept = push ? null : await readSecret();
    if (kept) {
      joinGroup(kept);
      return;
    }
    await writeToAccount(await ensureGroup());
  } catch {
    // Not signed in, or YouTube said no: the devices can still be added by hand.
  }
}

/** Not asked yet here: a group kept in the account means yes; none means ask. A failed look (signed out) asks nothing yet. */
async function lookInAccount(): Promise<void> {
  if (looking) return;
  looking = true;
  try {
    const { readSecret } = await import('./accountSync');
    const kept = await readSecret();
    if (useConnect.getState().saved.accountSync !== null) return;
    if (kept) {
      update({ accountSync: true });
      joinGroup(kept);
    } else {
      useConnect.setState({ askAccount: true });
    }
  } catch {
    // Signed out, or offline: looked at again on the next sign-in or visit.
  } finally {
    looking = false;
  }
}

/** The group, made now if this browser has none yet. */
export async function ensureGroup(): Promise<ConnectGroup> {
  const current = await ConnectGroup.fromText(useConnect.getState().saved.secret);
  if (current) return current;
  const fresh = await ConnectGroup.generate();
  // Nobody is told where it went: the devices left behind stay out.
  update({ secret: fresh.encoded, former: [] });
  restart();
  return fresh;
}

/**
 * Makes this browser one of the group in [text] (a link or the secret); false
 * if it isn't one. With [bring], the devices of its old group come along:
 * pairing only ever adds devices, it never leaves some behind.
 */
export function joinGroup(text: string, bring = true): boolean {
  const raw = ConnectGroup.parse(text);
  if (!raw) return false;
  if (raw !== useConnect.getState().saved.secret) {
    if (bring && session) {
      took = session.tookAt;
      session.stop(raw);
      session = null;
    }
    // The old group is listened to for a while longer, for its devices that were off just now.
    const { secret: old, former } = useConnect.getState().saved;
    update({ secret: raw, former: [...(old ? [old] : []), ...former].filter((f, i, all) => f !== raw && all.indexOf(f) === i).slice(0, MAX_FORMER) });
    restart();
  }
  return true;
}

/** A new secret: every other device falls out until it's added again. */
export async function disconnectAll(): Promise<void> {
  const fresh = await ConnectGroup.generate();
  update({ secret: fresh.encoded });
  restart();
  await syncAccount(true);
}

export const transferTo = (deviceId: string) => session?.transferTo(deviceId);
/** Autoplay on or off on the device playing elsewhere. */
export function toggleRemoteAutoplay(): void {
  const target = controllingOf(useConnect.getState().view);
  const on = target?.state?.autoplay;
  if (target && typeof on === 'boolean') session?.intercept('autoplay', 0, on ? 0 : 1);
}
/** Plays, or takes out, entry [at] ([track]) of the Up Next on the device playing elsewhere. */
export function playQueuedRemote(at: number, track: CTrack): void {
  const target = controllingOf(useConnect.getState().view);
  if (target) session?.jumpOn(target.id, at, track);
}
export function removeQueuedRemote(at: number, track: CTrack): void {
  const target = controllingOf(useConnect.getState().view);
  if (target) session?.removeOn(target.id, at, track, () => toast(`Couldn’t remove it on ${target.name || 'the other device'}`));
}
/**
 * Moves entry [from] ([track]) of the Up Next on the device playing elsewhere
 * to [to]: shown at once, put back with a message if that device says no
 * (one too old to know `move` does).
 */
export function moveQueuedRemote(from: number, to: number, track: CTrack): void {
  const target = controllingOf(useConnect.getState().view);
  if (!target) return;
  session?.moveOn(target.id, from, to, track, () =>
    toast(`${target.name || 'The other device'} couldn’t reorder its Up Next. Updating NepTune there adds it.`),
  );
}
/** Seeks the device playing elsewhere to [ms]. */
export const seekRemote = (ms: number) => session?.intercept('seek', Math.round(ms)) ?? false;
export const sendVolume = (value: number) => session?.intercept('volume', 0, value) ?? false;
