// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import { useToast } from '@/components/Toast';
import type { MusicItem, Track } from '@/data/music/types';
import { usePlayer } from '@/state/player';
import { TrackMenu } from './TrackMenu';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const track = (id: string, title: string): Track => ({
  videoId: id,
  title,
  artists: [{ name: 'An Artist', id: 'UCartist' }],
  album: { name: 'An Album', id: 'MPREb_album' },
  durationSec: 200,
  explicit: false,
  thumbnail: null,
  index: null,
  plays: null,
});

let root: ReturnType<typeof createRoot> | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

function render(item: Track | MusicItem) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } });
  act(() =>
    root!.render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <TrackMenu track={item} />
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  );
}

const button = (label: string) =>
  [...document.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label);

it('adds a song to the end of the queue and says so', () => {
  usePlayer.setState({ queue: [], index: -1 });
  render(track('song00000001', 'First Song'));
  act(() => button('More options for First Song')!.click());
  expect(button('Play Next')).toBeTruthy();
  expect(button('Start Radio')).toBeTruthy();
  expect(button('Go to Artist')).toBeTruthy();
  expect(button('Go to Album')).toBeTruthy();
  act(() => button('Add to Queue')!.click());
  expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toContain('song00000001');
  expect(useToast.getState().message).toBe('Added to queue: First Song');
  // The menu closes once a choice is made.
  expect(button('Play Next')).toBeUndefined();
});

it('plays a song next, ahead of what was already queued', () => {
  usePlayer.setState({ queue: [], index: -1 });
  usePlayer.getState().addToQueue([track('playing00001', 'Playing'), track('queued000001', 'Queued')]);
  render(track('song00000002', 'Second Song'));
  act(() => button('More options for Second Song')!.click());
  act(() => button('Play Next')!.click());
  const { queue, index } = usePlayer.getState();
  const ids = queue.map((e) => e.track.videoId);
  // Straight after what's playing, ahead of the song queued before it.
  expect(ids.indexOf('song00000002')).toBe(Math.max(index, 0) + 1);
  expect(ids.indexOf('song00000002')).toBeLessThan(ids.indexOf('queued000001'));
  expect(useToast.getState().message).toBe('Playing next: Second Song');
});

it('works on shelf and search items too, not only list rows', () => {
  usePlayer.setState({ queue: [], index: -1 });
  const item: MusicItem = {
    kind: 'song', id: 'shelfsong001', title: 'From a Shelf', subtitle: 'Song • Someone',
    artists: [{ name: 'Someone', id: null }], album: null, thumbnail: null, wide: false, durationSec: 180, explicit: false,
  };
  render(item);
  act(() => button('More options for From a Shelf')!.click());
  // No artist channel or album to go to: those choices aren't offered.
  expect(button('Go to Artist')).toBeUndefined();
  expect(button('Go to Album')).toBeUndefined();
  act(() => button('Add to Queue')!.click());
  expect(usePlayer.getState().queue.map((e) => e.track.videoId)).toContain('shelfsong001');
});
