// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Track } from '@/data/music/types';

const track = (id: string, title: string): Track => ({
  videoId: id,
  title,
  artists: [{ name: 'An Artist', id: null }],
  album: null,
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
});

beforeEach(() => {
  localStorage.clear();
  vi.resetModules();
  vi.useFakeTimers();
});
afterEach(() => vi.useRealTimers());

it('keeps the queue across a reload and opens it paused where it was left', async () => {
  const first = await import('./player');
  first.usePlayer.getState().addToQueue([track('song00000001', 'One'), track('song00000002', 'Two')]);
  vi.advanceTimersByTime(1500);
  const saved = JSON.parse(localStorage.getItem('opentune:session')!);
  expect(saved.queue.map((e: { track: Track }) => e.track.videoId)).toEqual(['song00000001', 'song00000002']);

  // A later visit: the place in the song came along too.
  localStorage.setItem('opentune:session', JSON.stringify({ ...saved, positionSec: 83 }));
  vi.resetModules();
  const { usePlayer, resumePosition } = await import('./player');
  const state = usePlayer.getState();
  expect(state.queue.map((e) => e.track.videoId)).toEqual(['song00000001', 'song00000002']);
  expect(state.index).toBe(0);
  expect(state.status).toBe('paused');
  expect(state.nowPlaying?.title).toBe('One');
  expect(resumePosition()).toBe(83);
});

it('ignores a broken saved queue', async () => {
  localStorage.setItem('opentune:session', '{"queue":"nope","index":3}');
  const { usePlayer } = await import('./player');
  expect(usePlayer.getState().queue).toEqual([]);
  expect(usePlayer.getState().nowPlaying).toBeNull();
});

it('lets the sleep timer stop at the end of the song instead of moving on', async () => {
  const { usePlayer } = await import('./player');
  const { useSleepTimer, sleepLabel } = await import('./sleepTimer');
  useSleepTimer.getState().set('end');
  expect(sleepLabel(useSleepTimer.getState().endsAt, useSleepTimer.getState().endOfSong)).toBe('At the end of this song');
  useSleepTimer.getState().set(30);
  const { endsAt, endOfSong } = useSleepTimer.getState();
  expect(endOfSong).toBe(false);
  expect(sleepLabel(endsAt, endOfSong, endsAt! - 23 * 60_000 + 1)).toBe('23 min left');
  useSleepTimer.getState().set(null);
  expect(useSleepTimer.getState().endsAt).toBeNull();
  expect(usePlayer.getState().status).toBe('idle');
});

it('remembers recent searches, newest first, without repeats', async () => {
  const { useRecentSearches } = await import('@/lib/recentSearches');
  const { add, remove } = useRecentSearches.getState();
  add('radiohead');
  add('Bjork');
  add('Radiohead');
  expect(useRecentSearches.getState().recent).toEqual(['Radiohead', 'Bjork']);
  remove('Bjork');
  expect(JSON.parse(localStorage.getItem('opentune:recent-searches')!)).toEqual(['Radiohead']);
});

it('queues a whole album after what is playing, credited to the album', async () => {
  const { usePlayer } = await import('./player');
  const { queueList } = await import('@/components/music/play');
  const { useToast } = await import('@/components/Toast');
  usePlayer.setState({ queue: [], index: -1 });
  usePlayer.getState().addToQueue([track('playing00001', 'Playing')]);
  const albumTrack = { ...track('album0000001', 'Opener'), artists: [] };
  queueList([albumTrack], { source: { title: 'An Album', type: 'album', id: 'MPREb_x' }, fallbackArtists: [{ name: 'Band', id: null }] }, true);
  const queued = usePlayer.getState().queue.find((e) => e.track.videoId === 'album0000001')!;
  expect(queued.track.artists[0]?.name).toBe('Band');
  expect(queued.source?.title).toBe('An Album');
  expect(useToast.getState().message).toBe('Playing next: An Album');
});

it('starts the next song from zero, not where the restored one was left', async () => {
  localStorage.setItem(
    'opentune:session',
    JSON.stringify({
      index: 0,
      positionSec: 40,
      queue: [
        { entryId: 'e1', tier: 'user', source: null, track: track('song00000001', 'One') },
        { entryId: 'e2', tier: 'user', source: null, track: track('song00000002', 'Two') },
      ],
    }),
  );
  const { usePlayer, resumePosition } = await import('./player');
  expect(resumePosition()).toBe(40);
  usePlayer.getState().next();
  await vi.waitFor(() => expect(usePlayer.getState().nowPlaying?.title).toBe('Two'));
  expect(resumePosition()).toBe(0);
});

it('turns loud songs down to the reference level and leaves quiet ones alone', async () => {
  const { normalizationGain } = await import('./player');
  expect(normalizationGain(6, true)).toBeCloseTo(0.501, 3);
  expect(normalizationGain(0, true)).toBe(1);
  expect(normalizationGain(-4, true)).toBe(1);
  expect(normalizationGain(null, true)).toBe(1);
  expect(normalizationGain(6, false)).toBe(1);
});

it('keeps loudness normalization on by default and remembers turning it off', async () => {
  const first = await import('./player');
  expect(first.usePlayer.getState().normalize).toBe(true);
  first.usePlayer.getState().setNormalize(false);
  vi.resetModules();
  const second = await import('./player');
  expect(second.usePlayer.getState().normalize).toBe(false);
});
