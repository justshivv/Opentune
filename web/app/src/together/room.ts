import { create } from 'zustand';
import type { Track } from '@/data/music/types';
import { getAudio, partyPlayer, setPartyHooks, usePlayer } from '@/state/player';
import { RoomCode } from './crypto';
import { RoomSession, type RoomPlayer, type RoomTrack, type RoomView } from './session';

/*
 * Listen Together in the web player: Together.kt's front door. One room at a
 * time; the session itself is session.ts. The same rooms as the Android app,
 * over the same relays, so a browser and phones can be in one room.
 */

const NAME_KEY = 'opentune:together-name';
const ASK_KEY = 'opentune:together-ask';
const read = (key: string) => { try { return localStorage.getItem(key); } catch { return null; } };
const write = (key: string, value: string) => { try { localStorage.setItem(key, value); } catch { /* This session only. */ } };

export interface Notice {
  text: string;
  at: number;
}
export const useRoom = create<{ view: RoomView | null; notice: Notice | null; starting: boolean }>(() => ({ view: null, notice: null, starting: false }));

export const savedName = () => read(NAME_KEY) ?? '';
export const savedAskToJoin = () => read(ASK_KEY) !== 'false';
export const parseCode = RoomCode.parse;

const notice = (text: string) => useRoom.setState({ notice: { text, at: Date.now() } });

// ---- Tracks across the boundary --------------------------------------------------------

const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;
const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;

/** The room's form of [track], or null for one other devices couldn't play. */
export function toRoomTrack(track: Track | undefined | null): RoomTrack | null {
  if (!track || !YOUTUBE_ID.test(track.videoId)) return null;
  return {
    id: track.videoId,
    title: track.title,
    artist: track.artists.map((a) => a.name).join(', '),
    thumb: track.thumbnail,
    length: track.durationSec ? clock(track.durationSec) : null,
    album: track.album?.name ?? null,
  };
}

function fromRoomTrack(t: RoomTrack): Track {
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

// ---- The player, as a room sees it -----------------------------------------------------

const webPlayer: RoomPlayer = {
  currentId: () => usePlayer.getState().nowPlaying?.videoId ?? null,
  current: () => {
    const { queue, index } = usePlayer.getState();
    return toRoomTrack(queue[index]?.track);
  },
  upcoming: () => {
    const { queue, index } = usePlayer.getState();
    return queue.slice(index + 1).map((e) => toRoomTrack(e.track)).filter((t): t is RoomTrack => !!t);
  },
  upcomingIds: () => webPlayer.upcoming().map((t) => t.id),
  playing: () => {
    const audio = getAudio();
    return !!audio && !audio.paused;
  },
  buffering: () => ['loading', 'buffering'].includes(usePlayer.getState().status),
  positionMs: () => Math.max(0, (getAudio()?.currentTime ?? 0) * 1000),
  setRate: (rate) => partyPlayer.setRate(rate),
  play: () => partyPlayer.play(),
  pause: () => partyPlayer.pause(),
  seekTo: (ms) => partyPlayer.seekTo(ms / 1000),
  load: (tracks, startMs, play) => void partyPlayer.load(tracks.map(fromRoomTrack), tracks.map(() => 'user'), 0, startMs / 1000, play),
  replaceUpcoming: (tracks) => partyPlayer.setUpcoming(tracks.map(fromRoomTrack), tracks.map(() => 'user')),
  addToQueue: (t) => usePlayer.getState().addToQueue([fromRoomTrack(t)]),
  playNow: (t) => {
    usePlayer.getState().playNext([fromRoomTrack(t)]);
    usePlayer.getState().next();
  },
  skipNext: () => usePlayer.getState().next(),
  skipPrevious: () => usePlayer.getState().previous(),
};

// ---- One room at a time -----------------------------------------------------------------

let session: RoomSession | null = null;
let unsubscribe: (() => void) | null = null;

const following = () => !!session && !session.hosting && session.view.phase === 'live';

setPartyHooks({
  onIntent: () => session?.playerChanged(),
  intercept: (control) => session?.intercept(control) ?? false,
  suggest: (tracks, now) => {
    if (!session || !following()) return false;
    const shared = tracks.map(toRoomTrack).filter((t): t is RoomTrack => !!t);
    if (!shared.length) {
      notice("That song can't be shared to the room");
      return true;
    }
    if (now) session.suggest(shared[0]!, true);
    else shared.forEach((t) => session!.suggest(t, false));
    return true;
  },
  inParty: following,
});

async function start(raw: string, name: string, hosting: boolean): Promise<void> {
  leaveRoom();
  const clean = name.trim().slice(0, 30) || 'Someone';
  write(NAME_KEY, clean);
  useRoom.setState({ starting: true });
  try {
    const code = await RoomCode.of(raw);
    const s = new RoomSession(code, clean, hosting, webPlayer, (view) => { if (session === s) useRoom.setState({ view }); }, notice, savedAskToJoin());
    session = s;
    useRoom.setState({ view: s.view });
    // The host's playback changing by itself (a song ending, buffering) goes out too.
    unsubscribe = usePlayer.subscribe((now, before) => {
      if (now.nowPlaying?.videoId !== before.nowPlaying?.videoId || now.status !== before.status || now.queue !== before.queue) s.playerChanged();
    });
    await s.start();
  } finally {
    useRoom.setState({ starting: false });
  }
}

/** Opens a new room hosted here; resolves with its code. */
export async function createRoom(name: string): Promise<string> {
  const raw = RoomCode.generate();
  await start(raw, name, true);
  return raw;
}

/** Joins [code] (in any typed or pasted form); false when it isn't a room code. */
export async function joinRoom(code: string, name: string): Promise<boolean> {
  const raw = RoomCode.parse(code);
  if (!raw) return false;
  if (session?.view.code === raw && session.view.phase !== 'ended') return true;
  await start(raw, name, false);
  return true;
}

export function leaveRoom(): void {
  unsubscribe?.();
  unsubscribe = null;
  session?.stop(true);
  session = null;
  partyPlayer.setRate(1);
  useRoom.setState({ view: null });
}

export const approve = (id: string) => session?.approve(id);
export const reject = (id: string) => session?.reject(id);
export const removeMember = (id: string) => void session?.remove(id);
export const setOpen = (on: boolean) => session?.setOpen(on);
export const say = (text: string) => session?.say(text);
export function setAskToJoin(on: boolean) {
  write(ASK_KEY, String(on));
  session?.setAskToJoin(on);
}

/** A room that ended (the host left, or this device was turned away) leaves its last view up until dismissed. */
export function dismissEnded(): void {
  if (useRoom.getState().view?.phase === 'ended') leaveRoom();
}

// Closing the tab says goodbye, so the others see this device leave at once.
addEventListener('pagehide', () => session?.stop(true));
