import { BridgeCallError, type YtAuthState } from '@opentune/shared/bridge';

/**
 * Loading the YouTube account from the extension, one load at a time. Every
 * caller (the sidebar, Settings, the music client, history reporting, a focus
 * refetch) shares the load in flight. A load that fails in a passing way (no
 * answer yet, YouTube slow or rate limiting) is tried again after 2, 5 and
 * 12 s, or after the wait YouTube asked for; only the final outcome reaches
 * the page, so it never flickers between "failed" and "loading".
 */

export const AUTH_RETRY_DELAYS_MS: readonly number[] = [2000, 5000, 12_000];
/** A Retry-After longer than this isn't waited out here; the Retry button is there for later. */
const MAX_WAIT_MS = 60_000;

/** Bridge failures worth another try: no answer, a waking worker, or the network. */
export function isPassingError(error: unknown): boolean {
  if (!(error instanceof BridgeCallError)) return false;
  return error.code === 'timeout' || error.code === 'unavailable' || error.code === 'network' || error.code === 'internal';
}

/**
 * Signed in to the browser, connected, and still no account: YouTube didn't
 * answer the extension's read. An extension from 0.2.1 says whether that was
 * passing (`retryable`); an older one doesn't say, so it's assumed.
 */
export function isPassingFailure(state: YtAuthState): boolean {
  return state.browserSignedIn && state.connected && !state.active && state.retryable !== false;
}

export interface AuthLoaderOptions {
  /** One read from the extension; [refresh] asks it to read YouTube again rather than answer from its cache. */
  read: (refresh: boolean) => Promise<YtAuthState>;
  /** Called once per load with its final state. */
  onResult?: (state: YtAuthState) => void;
  delays?: readonly number[];
  wait?: (ms: number) => Promise<void>;
}

const pause = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export function createAuthLoader({ read, onResult, delays = AUTH_RETRY_DELAYS_MS, wait = pause }: AuthLoaderOptions) {
  let inflight: { refresh: boolean; promise: Promise<YtAuthState> } | null = null;

  async function run(refresh: boolean): Promise<YtAuthState> {
    let again = refresh;
    for (let attempt = 0; ; attempt++) {
      let delay: number;
      try {
        const state = await read(again);
        if (!isPassingFailure(state) || attempt >= delays.length) {
          onResult?.(state);
          return state;
        }
        delay = Math.min(MAX_WAIT_MS, Math.max(delays[attempt]!, state.retryAfterMs ?? 0));
        // Before 0.2.1 the extension held a failed read for ten minutes unless told to read again.
        again = state.retryable === undefined || refresh;
      } catch (error) {
        if (!isPassingError(error) || attempt >= delays.length) throw error;
        delay = delays[attempt]!;
      }
      await wait(delay);
    }
  }

  /** The load in flight, or a new one. A refresh joins only a refresh: a plain load might answer from a stale cache. */
  return function load(refresh = false): Promise<YtAuthState> {
    if (inflight && (inflight.refresh || !refresh)) return inflight.promise;
    const promise = run(refresh).finally(() => {
      if (inflight?.promise === promise) inflight = null;
    });
    inflight = { refresh, promise };
    return promise;
  };
}

// ---- Last known account -----------------------------------------------------------

const LAST_KEY = 'neptune:yt-account';

/** What the sidebar shows while the account loads: a name and a picture, nothing else. */
export interface LastKnownAccount {
  name: string;
  avatarUrl: string | null;
}

export function readLastKnown(): LastKnownAccount | null {
  try {
    const raw = localStorage.getItem(LAST_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<LastKnownAccount>;
    return typeof parsed.name === 'string' ? { name: parsed.name, avatarUrl: typeof parsed.avatarUrl === 'string' ? parsed.avatarUrl : null } : null;
  } catch {
    return null;
  }
}

/** Keeps the active channel's name and picture; a clear sign-out or disconnect forgets them; a failed read leaves them. */
export function noteLastKnown(state: YtAuthState): void {
  try {
    if (state.active) {
      localStorage.setItem(LAST_KEY, JSON.stringify({ name: state.active.name, avatarUrl: state.active.avatarUrl }));
    } else if (!state.browserSignedIn || !state.connected) {
      localStorage.removeItem(LAST_KEY);
    }
  } catch {
    /* Shown without it next time. */
  }
}
