import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { GIVE_UP_AFTER, isDeadRelay, RelayPool, resetRelayHealth } from './nostr';

/** A socket to a relay that is down: it closes as soon as it's made. */
class DeadSocket {
  static made: string[] = [];
  static OPEN = 1;
  readyState = 3;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((m: { data: unknown }) => void) | null = null;
  constructor(readonly url: string) {
    DeadSocket.made.push(url);
    setTimeout(() => this.onclose?.(), 0);
  }
  send() {}
  close() {}
}

beforeEach(() => {
  vi.useFakeTimers();
  resetRelayHealth();
  DeadSocket.made = [];
  vi.stubGlobal('WebSocket', DeadSocket);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it('backs off from a dead relay and stops trying it for the session', async () => {
  const pool = new RelayPool(['wss://dead.example'], {}, () => {}, () => {});
  pool.start();
  await vi.advanceTimersByTimeAsync(30 * 60_000);
  expect(DeadSocket.made.length).toBe(GIVE_UP_AFTER);
  expect(isDeadRelay('wss://dead.example')).toBe(true);
  // A pool started later (Listen Together after Connect) doesn't try it either.
  new RelayPool(['wss://dead.example'], {}, () => {}, () => {}).start();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(DeadSocket.made.length).toBe(GIVE_UP_AFTER);
  pool.close();
});
