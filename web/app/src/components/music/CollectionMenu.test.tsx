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
  browserSignedIn: true, connected: true, signedIn: true, accounts: [],
  active: { id: '0:own', name: 'Listener', handle: null, avatarUrl: null, brand: false, authUser: '0' }, problem: null,
};
const SIGNED_OUT: YtAuthState = { ...SIGNED_IN, browserSignedIn: false, signedIn: false, active: null };

const mocks = vi.hoisted(() => ({
  auth: { state: 'checking', lastKnown: null } as YtAuthStatus,
  setListSaved: vi.fn<(playlistId: string, saved: boolean) => Promise<void>>(),
  setArtistSubscribed: vi.fn<(channelId: string, subscribed: boolean) => Promise<void>>(),
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
  setListSaved: mocks.setListSaved,
  setArtistSubscribed: mocks.setArtistSubscribed,
}));

const { CircleLockup, SquareLockup } = await import('./Lockups');
const { queryClient } = await import('@/lib/queryClient');

/** An invented playlist, its first page already in the cache as its page would leave it. */
const card: MusicItem = {
  kind: 'playlist', id: 'VLPLlantern', title: 'Lantern Street Mix', subtitle: 'Playlist • Someone',
  artists: [], album: null, thumbnail: null, wide: false, durationSec: null, explicit: false,
};
function cachePlaylist(saved: boolean) {
  queryClient.setQueryData(['music', 'playlist', card.id], {
    pages: [{
      header: { title: card.title, subtitle: '', meta: '', description: null, thumbnail: null, artists: [], explicit: false,
        library: { playlistId: 'PLlantern', saved } },
      tracks: [], next: null,
    }],
    pageParams: [null],
  });
}

let root: ReturnType<typeof createRoot> | null = null;
beforeEach(() => {
  queryClient.clear();
  mocks.auth = { state: 'ready', auth: SIGNED_IN };
  mocks.setListSaved.mockReset().mockResolvedValue(undefined);
  mocks.setArtistSubscribed.mockReset().mockResolvedValue(undefined);
});
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

async function rightClick(node: React.ReactNode = <SquareLockup item={card} />) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(<QueryClientProvider client={queryClient}><MemoryRouter>{node}</MemoryRouter></QueryClientProvider>));
  const target = host.firstElementChild!;
  await act(async () => { target.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 10, clientY: 10 })); });
}
const item = (label: string) => [...document.querySelectorAll<HTMLElement>('[role^="menuitem"]')].find((b) => b.textContent?.trim() === label);
const until = (check: () => void) => act(async () => { await vi.waitFor(check, { timeout: 2000, interval: 5 }); });

it('a right-click on a playlist opens its menu, with Add to Library for a list not saved', async () => {
  cachePlaylist(false);
  await rightClick();
  for (const label of ['Play', 'Shuffle', 'Play Next', 'Add to Queue', 'Go to Playlist', 'Copy Link']) expect(item(label), label).toBeTruthy();
  await until(() => expect(item('Add to Library')).toBeTruthy());
  await act(async () => item('Add to Library')!.click());
  await until(() => expect(mocks.setListSaved).toHaveBeenCalledWith('PLlantern', true));
});

it('a saved playlist offers Remove from Library', async () => {
  cachePlaylist(true);
  await rightClick();
  await until(() => expect(item('Remove from Library')).toBeTruthy());
  await act(async () => item('Remove from Library')!.click());
  await until(() => expect(mocks.setListSaved).toHaveBeenCalledWith('PLlantern', false));
});

it('signed out, the menu has no library item', async () => {
  mocks.auth = { state: 'ready', auth: SIGNED_OUT };
  cachePlaylist(false);
  await rightClick();
  expect(item('Play')).toBeTruthy();
  expect(item('Add to Library')).toBeFalsy();
});

/** An invented artist, their page already in the cache. */
const singer: MusicItem = { ...card, kind: 'artist', id: 'UCivo', title: 'Ivo Marlowe', subtitle: 'Artist' };
function cacheArtist(subscribed: boolean) {
  queryClient.setQueryData(['music', 'artist', singer.id], {
    id: singer.id, name: singer.title, description: null, heroImage: null, audience: null,
    subscription: { channelId: 'UCivo', subscribed }, topSongs: [], topSongsSeeAll: null, shelves: [],
  });
}

it('a right-click on an artist opens their menu, with Subscribe synced to the account', async () => {
  cacheArtist(false);
  await rightClick(<CircleLockup item={singer} />);
  for (const label of ['Play', 'Shuffle', 'Start Radio', 'Go to Artist', 'Copy Link']) expect(item(label), label).toBeTruthy();
  await until(() => expect(item('Subscribe')).toBeTruthy());
  await act(async () => item('Subscribe')!.click());
  await until(() => expect(mocks.setArtistSubscribed).toHaveBeenCalledWith('UCivo', true));
});

it('a subscribed artist offers Unsubscribe', async () => {
  cacheArtist(true);
  await rightClick(<CircleLockup item={singer} />);
  await until(() => expect(item('Unsubscribe')).toBeTruthy());
});
