// @vitest-environment happy-dom
import type { YtAuthState } from '@opentune/shared/bridge';
import { QueryClientProvider } from '@tanstack/react-query';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { MusicItem } from '@/data/music/types';
import type { YtAuthStatus } from '@/extension/useYtAuth';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const SIGNED_IN: YtAuthState = {
  browserSignedIn: true,
  connected: true,
  signedIn: true,
  accounts: [],
  active: { id: '0:own', name: 'Listener', handle: null, avatarUrl: null, brand: false, authUser: '0' },
  problem: null,
};
const SIGNED_OUT: YtAuthState = { ...SIGNED_IN, browserSignedIn: false, signedIn: false, active: null };

const mocks = vi.hoisted(() => ({
  auth: { state: 'checking', lastKnown: null } as YtAuthStatus,
  fetchPlaylistOptions: vi.fn<(videoId: string) => Promise<{ playlistId: string; title: string }[]>>(),
  fetchLikeStatus: vi.fn<(videoId: string) => Promise<'LIKE' | 'INDIFFERENT' | 'DISLIKE'>>(),
  setTrackLiked: vi.fn<(videoId: string, liked: boolean) => Promise<void>>(),
}));

vi.mock('@/extension/useYtAuth', () => ({
  useYtAuth: () => mocks.auth,
  ytAuthSnapshot: () => (mocks.auth.state === 'ready' ? mocks.auth.auth : SIGNED_OUT),
  ensureYtAuth: async () => (mocks.auth.state === 'ready' ? mocks.auth.auth : SIGNED_OUT),
  identityOf: () => 'anonymous',
  noteMusicIdentity: () => undefined,
}));
vi.mock('@/data/music/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/data/music/api')>()),
  fetchPlaylistOptions: mocks.fetchPlaylistOptions,
  fetchLikeStatus: mocks.fetchLikeStatus,
  setTrackLiked: mocks.setTrackLiked,
}));

const { TrackMenu } = await import('./TrackMenu');
const { FavoriteButton } = await import('./FavoriteButton');
const { MENU_TIMEOUT_MS } = await import('@/data/music/queries');
const { queryClient } = await import('@/lib/queryClient');
const { useToast } = await import('@/components/Toast');

/** A song as a Home shelf card carries it: no index, plays, or like status of its own. */
const card: MusicItem = {
  kind: 'song',
  id: 'card0000001',
  title: 'Card Song',
  subtitle: 'Song • Someone',
  artists: [{ name: 'Someone', id: null }],
  album: null,
  thumbnail: null,
  wide: false,
  durationSec: null,
  explicit: false,
};

const never = <T,>() => new Promise<T>(() => {});

let root: ReturnType<typeof createRoot> | null = null;
beforeEach(() => {
  queryClient.clear();
  mocks.auth = { state: 'ready', auth: SIGNED_IN };
  mocks.fetchPlaylistOptions.mockReset();
  mocks.fetchLikeStatus.mockReset().mockImplementation(never);
  mocks.setTrackLiked.mockReset().mockResolvedValue(undefined);
  useToast.setState({ message: null } as never);
});
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
  vi.useRealTimers();
});

async function render(node: React.ReactNode) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () =>
    root!.render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{node}</MemoryRouter>
      </QueryClientProvider>,
    ),
  );
}

const button = (label: string) =>
  [...document.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim().startsWith(label) || b.getAttribute('aria-label') === label,
  );
const text = () => document.body.textContent ?? '';
/** Waits, under act, until [check] passes: queries and mutations settle over a few ticks. */
const until = (check: () => void) =>
  act(async () => {
    await vi.waitFor(check, { timeout: 2000, interval: 5 });
  });

async function openPlaylists() {
  await render(<TrackMenu track={card} />);
  await act(async () => button('More options for Card Song')!.click());
  await act(async () => button('Add to Playlist')!.click());
}

it('Add to Playlist leaves "Loading" when the request fails, with Try Again and New Playlist', async () => {
  mocks.fetchPlaylistOptions.mockRejectedValueOnce(new Error('Not signed in to YouTube Music.'));
  await openPlaylists();
  await until(() => expect(text()).toContain('Couldn’t load your playlists'));
  expect(text()).not.toContain('Loading');
  expect(button('New Playlist')).toBeTruthy();

  mocks.fetchPlaylistOptions.mockResolvedValueOnce([{ playlistId: 'PL1', title: 'Road Trip' }]);
  await act(async () => button('Try Again')!.click());
  await until(() => expect(button('Road Trip')).toBeTruthy());
});

it('Add to Playlist gives up on a request that never answers', async () => {
  vi.useFakeTimers();
  mocks.fetchPlaylistOptions.mockImplementation(never);
  await openPlaylists();
  expect(text()).toContain('Loading your playlists');
  await act(async () => {
    await vi.advanceTimersByTimeAsync(MENU_TIMEOUT_MS + 10);
  });
  expect(text()).not.toContain('Loading your playlists');
  expect(text()).toContain('Couldn’t load your playlists');
  expect(button('Try Again')).toBeTruthy();
});

it('Add to Playlist says to sign in when there is no account, instead of loading', async () => {
  mocks.auth = { state: 'ready', auth: SIGNED_OUT };
  await openPlaylists();
  expect(text()).toContain('Sign in to add to your YouTube Music playlists');
  expect(text()).not.toContain('Loading');
  expect(mocks.fetchPlaylistOptions).not.toHaveBeenCalled();
});

it('Favorite works on a card song while its like status is still unknown', async () => {
  await render(<TrackMenu track={card} />);
  await act(async () => button('More options for Card Song')!.click());
  const favorite = button('Favorite')!;
  expect(favorite.disabled).toBe(false);
  await act(async () => favorite.click());
  await until(() => expect(mocks.setTrackLiked).toHaveBeenCalledWith('card0000001', true));
});

it('Favorite is unavailable only without an account, and says why', async () => {
  mocks.auth = { state: 'ready', auth: SIGNED_OUT };
  await render(<TrackMenu track={card} />);
  await act(async () => button('More options for Card Song')!.click());
  const favorite = button('Favorite')!;
  expect(favorite.disabled).toBe(true);
  expect(favorite.textContent).toContain('Sign in to like songs');
});

it('the player’s star turns at once, and turns back with one message if YouTube refuses', async () => {
  let refuse!: (error: Error) => void;
  mocks.setTrackLiked.mockImplementationOnce(() => new Promise((_, reject) => (refuse = reject)));
  await render(<FavoriteButton videoId="card0000001" />);
  const star = button('Favorite')!;
  expect(star.disabled).toBe(false);
  await act(async () => star.click());
  await until(() => expect(button('Unfavorite')?.getAttribute('aria-pressed')).toBe('true'));
  await until(() => expect(mocks.setTrackLiked).toHaveBeenCalledWith('card0000001', true));
  await act(async () => refuse(new Error('HTTP 401')));
  await until(() => expect(button('Favorite')?.getAttribute('aria-pressed')).toBe('false'));
  expect(useToast.getState().message).toBe('Couldn’t add it to Favorites. Try again.');
});
