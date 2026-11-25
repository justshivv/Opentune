// @vitest-environment happy-dom
import { BridgeCallError, type YtAuthState } from '@opentune/shared/bridge';
import { beforeEach, expect, it, vi } from 'vitest';
import { createAuthLoader, noteLastKnown, readLastKnown } from './authLoader';

const signedIn: YtAuthState = {
  browserSignedIn: true,
  connected: true,
  signedIn: true,
  accounts: [],
  active: { id: '0:own', name: 'Shiv', handle: null, avatarUrl: 'https://yt3.example/a.jpg', brand: false, authUser: '0' },
  problem: null,
};
const failed = (extra: Partial<YtAuthState> = {}): YtAuthState => ({
  ...signedIn,
  signedIn: false,
  active: null,
  problem: 'YouTube didn’t answer in time',
  ...extra,
});

beforeEach(() => localStorage.clear());

function loader(read: (refresh: boolean) => Promise<YtAuthState>) {
  const waits: number[] = [];
  const onResult = vi.fn();
  const load = createAuthLoader({ read, onResult, wait: async (ms) => void waits.push(ms) });
  return { load, waits, onResult };
}

it('shares one load between every caller', async () => {
  let release!: (state: YtAuthState) => void;
  const read = vi.fn(() => new Promise<YtAuthState>((resolve) => (release = resolve)));
  const { load, onResult } = loader(read);
  const a = load();
  const b = load();
  const c = load();
  release(signedIn);
  expect(await Promise.all([a, b, c])).toEqual([signedIn, signedIn, signedIn]);
  expect(read).toHaveBeenCalledTimes(1);
  expect(onResult).toHaveBeenCalledTimes(1);
  // Once settled, the next call reads again.
  read.mockResolvedValueOnce(signedIn);
  await load();
  expect(read).toHaveBeenCalledTimes(2);
});

it('a refresh does not join a plain load, but a plain load joins a refresh', async () => {
  const read = vi.fn(async (_refresh: boolean) => signedIn);
  const { load } = loader(read);
  await Promise.all([load(false), load(true)]);
  expect(read.mock.calls.map((c) => c[0])).toEqual([false, true]);
  read.mockClear();
  await Promise.all([load(true), load(false)]);
  expect(read.mock.calls.map((c) => c[0])).toEqual([true]);
});

it('retries a passing failure with backoff, then settles on the answer', async () => {
  const read = vi
    .fn<(refresh: boolean) => Promise<YtAuthState>>()
    .mockRejectedValueOnce(new BridgeCallError('timeout', 'No answer from the extension within 45000 ms'))
    .mockResolvedValueOnce(failed({ retryable: true, retryAfterMs: null }))
    .mockResolvedValueOnce(signedIn);
  const { load, waits, onResult } = loader(read);
  expect(await load()).toBe(signedIn);
  expect(waits).toEqual([2000, 5000]);
  // Only the final state reaches the page: no "failed" flash in between.
  expect(onResult).toHaveBeenCalledTimes(1);
  expect(onResult).toHaveBeenCalledWith(signedIn);
});

it('honours how long YouTube asked to wait', async () => {
  const read = vi
    .fn<(refresh: boolean) => Promise<YtAuthState>>()
    .mockResolvedValueOnce(failed({ retryable: true, retryAfterMs: 9000 }))
    .mockResolvedValueOnce(signedIn);
  const { load, waits } = loader(read);
  await load();
  expect(waits).toEqual([9000]);
});

it('asks an extension older than 0.2.1 to read again, since it holds failures', async () => {
  const read = vi
    .fn<(refresh: boolean) => Promise<YtAuthState>>()
    .mockResolvedValueOnce(failed()) // no `retryable`: an old extension
    .mockResolvedValueOnce(signedIn);
  const { load } = loader(read);
  await load();
  expect(read.mock.calls.map((c) => c[0])).toEqual([false, true]);
});

it('gives up after the retries with one final state, and never retries a clear answer', async () => {
  const read = vi.fn(async () => failed({ retryable: true }));
  const { load, waits, onResult } = loader(read);
  const state = await load();
  expect(state.problem).toBeTruthy();
  expect(read).toHaveBeenCalledTimes(4);
  expect(waits).toEqual([2000, 5000, 12_000]);
  expect(onResult).toHaveBeenCalledTimes(1);

  const clear = vi.fn(async () => failed({ retryable: false }));
  const second = loader(clear);
  await second.load();
  expect(clear).toHaveBeenCalledTimes(1);

  const refused = vi.fn(async (): Promise<YtAuthState> => {
    throw new BridgeCallError('forbidden', 'Origin isn’t allowed');
  });
  await expect(loader(refused).load()).rejects.toThrow('allowed');
  expect(refused).toHaveBeenCalledTimes(1);
});

it('keeps the last account’s name and picture through a failed read, and forgets them on sign-out', () => {
  noteLastKnown(signedIn);
  expect(readLastKnown()).toEqual({ name: 'Shiv', avatarUrl: 'https://yt3.example/a.jpg' });
  noteLastKnown(failed({ retryable: true }));
  expect(readLastKnown()?.name).toBe('Shiv');
  noteLastKnown({ ...failed(), browserSignedIn: false });
  expect(readLastKnown()).toBeNull();
  // Nothing but the name and picture is kept.
  noteLastKnown(signedIn);
  expect(Object.keys(JSON.parse(localStorage.getItem('neptune:yt-account')!))).toEqual(['name', 'avatarUrl']);
});
