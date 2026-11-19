import { beforeEach, describe, expect, it, vi } from 'vitest';

/*
 * What this browser's player tells the others while a song loads (a skip, a
 * handover): the audio element still holds the last song then.
 */

const fake = vi.hoisted(() => ({
  state: {} as Record<string, unknown>,
  audio: null as Record<string, unknown> | null,
  loading: null as { startSec: number; plays: boolean } | null,
}));

vi.mock('@/state/player', () => ({
  getAudio: () => fake.audio,
  loadingTrack: () => fake.loading,
  partyPlayer: {},
  setConnectHooks: () => {},
  usePlayer: { getState: () => fake.state, subscribe: () => () => {} },
}));
vi.mock('@/shell/quality', () => ({ qualityLabel: () => ({ text: 'High' }) }));
vi.mock('@/together/room', () => ({
  toRoomTrack: (t: { videoId: string; title: string } | null | undefined) => (t ? { id: t.videoId, title: t.title } : null),
}));

const { webPlayer } = await import('./connect');

const song = (id: string) => ({ track: { videoId: id.repeat(11), title: `Song ${id}`, artists: [] }, tier: 'user' });

describe("the web player's snapshot", () => {
  beforeEach(() => {
    (globalThis as { HTMLMediaElement?: unknown }).HTMLMediaElement ??= { HAVE_FUTURE_DATA: 3 };
    fake.state = { queue: [song('x'), song('y')], index: 0, nowPlaying: { videoId: 'x'.repeat(11), streamInfo: null }, volume: 1, shuffle: false, repeat: 'off', status: 'playing' };
    // Song x, 1:40 in and playing.
    fake.audio = { paused: false, seeking: false, readyState: 4, currentTime: 100 };
    fake.loading = null;
  });

  it('says where it is and that it moves while it plays', () => {
    const s = webPlayer.snapshot()!;
    expect(s.track?.id).toBe('x'.repeat(11));
    expect(s).toMatchObject({ playing: true, wants: true, pos: 100_000 });
  });

  it('gives the new song its own start while it loads, not the last song’s place', () => {
    // Skipped to y: the store has y, the element still plays x at 1:40.
    fake.state = { ...fake.state, index: 1, nowPlaying: { videoId: 'y'.repeat(11), streamInfo: null }, status: 'loading' };
    fake.loading = { startSec: 0, plays: true };
    const s = webPlayer.snapshot()!;
    expect(s.track?.id).toBe('y'.repeat(11));
    // Not moving (the others don't count it on), meant to play, from y's start.
    expect(s).toMatchObject({ playing: false, wants: true, pos: 0 });
  });

  it('is meant to play a handover it is loading, from where it was handed over', () => {
    // Handed song y at 1:00, playing; this tab was paused on x before.
    fake.audio = { paused: true, seeking: false, readyState: 4, currentTime: 100 };
    fake.state = { ...fake.state, index: 1, nowPlaying: { videoId: 'y'.repeat(11), streamInfo: null }, status: 'loading' };
    fake.loading = { startSec: 60, plays: true };
    expect(webPlayer.snapshot()).toMatchObject({ playing: false, wants: true, pos: 60_000 });
    // Handed it paused: it waits, paused, at its place.
    fake.loading = { startSec: 60, plays: false };
    expect(webPlayer.snapshot()).toMatchObject({ playing: false, wants: false, pos: 60_000 });
  });
});
