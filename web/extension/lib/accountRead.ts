/**
 * The parts of reading the account list that don't touch the browser: how a
 * failed request is told apart from a login that isn't there, retries with
 * backoff (honouring Retry-After), a small worker pool, merging a partial read
 * with the last good one, and how long each kind of result is kept. Pure, so
 * it runs under `node --test`; lib/ytAuth.ts does the requests.
 */

/** An HTTP answer that wasn't a success, with what it said about waiting. */
export class HttpStatusError extends Error {
  readonly status: number;
  readonly retryAfterMs: number | null;

  constructor(status: number, retryAfterMs: number | null = null) {
    super(`HTTP ${status}`);
    this.name = 'HttpStatusError';
    this.status = status;
    this.retryAfterMs = retryAfterMs;
  }
}

/** Statuses that say "not now" rather than "no": timeouts, rate limits and server errors. */
export function isTransientStatus(status: number): boolean {
  return status === 408 || status === 425 || status === 429 || (status >= 500 && status <= 599);
}

/** Whether asking again later might work: a passing HTTP status, or the request never got an answer. */
export function isTransient(error: unknown): boolean {
  if (error instanceof HttpStatusError) return isTransientStatus(error.status);
  // lib/http.ts reports timeouts, aborts and unreachable hosts with this code.
  return (error as { code?: unknown } | null)?.code === 'network';
}

/** A Retry-After header (seconds, or an HTTP date) in ms from [now]; null when absent or unreadable. */
export function parseRetryAfter(value: string | null | undefined, now = Date.now()): number | null {
  if (!value) return null;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed) * 1000;
  const at = Date.parse(trimmed);
  return Number.isNaN(at) ? null : Math.max(0, at - now);
}

export interface RetryOptions {
  /** Tries in all, the first included. */
  attempts?: number;
  /** The first pause; each later one doubles. */
  baseMs?: number;
  /** A Retry-After longer than this isn't waited for: the failure is reported instead. */
  maxWaitMs?: number;
  /** No retry is started that would end past this time (ms since the epoch). */
  deadline?: number;
  sleep?: (ms: number) => Promise<void>;
  now?: () => number;
}

export const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Runs [run], again after a pause when it fails in a passing way, up to [attempts] times. */
export async function withRetry<T>(run: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const { attempts = 2, baseMs = 600, maxWaitMs = 8000, deadline = Infinity, sleep: pause = sleep, now = Date.now } = options;
  for (let attempt = 1; ; attempt++) {
    try {
      return await run();
    } catch (error) {
      if (attempt >= attempts || !isTransient(error)) throw error;
      const asked = error instanceof HttpStatusError ? error.retryAfterMs : null;
      const wait = asked ?? baseMs * 2 ** (attempt - 1);
      // Waiting past what YouTube allows, or past the read's own deadline, helps nobody.
      if (wait > maxWaitMs || now() + wait >= deadline) throw error;
      await pause(wait);
    }
  }
}

/** [run] over [items], at most [limit] at a time, results in the items' order. */
export async function mapPool<T, R>(items: readonly T[], limit: number, run: (item: T, index: number) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await run(items[index]!, index);
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, worker));
  return results;
}

/** What one login index came to. */
export type LoginOutcome<A> =
  | { kind: 'account'; account: A }
  /** YouTube answered, and there is no login at this index. */
  | { kind: 'none'; why: string }
  /** No usable answer (slow, busy, unreachable): the index may well hold a login. */
  | { kind: 'failed'; why: string; retryAfterMs: number | null };

interface LoginLike {
  authUser: string;
  email: string | null;
  name: string;
  channels: { id: string }[];
}

export interface MergedLogins<A> {
  list: A[];
  /** Every index answered one way or the other. */
  complete: boolean;
  /** Why the indices that failed did, the first of them. */
  failedWhy: string | null;
  /** The longest Retry-After any of them was given. */
  retryAfterMs: number | null;
}

/**
 * The accounts from one read, in index order and without duplicates (one
 * Google login can answer at two indices). An index that failed keeps the
 * account [previous] had there, so one slow request doesn't drop a login
 * that was there a minute ago.
 */
export function mergeLogins<A extends LoginLike>(outcomes: readonly LoginOutcome<A>[], previous: readonly A[] | null): MergedLogins<A> {
  const list: A[] = [];
  const seen = new Set<string>();
  let complete = true;
  let failedWhy: string | null = null;
  let retryAfterMs: number | null = null;
  outcomes.forEach((outcome, index) => {
    let account: A | undefined;
    if (outcome.kind === 'account') account = outcome.account;
    else if (outcome.kind === 'failed') {
      complete = false;
      failedWhy ??= outcome.why;
      if (outcome.retryAfterMs !== null) retryAfterMs = Math.max(retryAfterMs ?? 0, outcome.retryAfterMs);
      account = previous?.find((a) => a.authUser === String(index));
    }
    if (!account) return;
    const key = account.email ?? `${account.name}|${account.channels.map((c) => c.id.split(':')[1]).join(',')}`;
    if (seen.has(key)) return;
    seen.add(key);
    list.push(account);
  });
  return { list, complete, failedWhy, retryAfterMs };
}

/** A full read: kept for ten minutes (a cookie change clears it sooner). */
export const COMPLETE_TTL_MS = 10 * 60_000;
/** Some logins found, some indices unanswered: used, and read again in a minute. */
export const PARTIAL_TTL_MS = 60_000;
/** Nothing found because YouTube didn't answer: asked again soon, never held for long. */
export const FAILED_TTL_MS = 15_000;
/** YouTube answered clearly that no login is there. */
export const EMPTY_TTL_MS = 2 * 60_000;

/** A stored read: `until` is when it stops being fresh (0.2.0 stored only `at`). */
export interface HeldRead {
  list: readonly unknown[];
  at: number;
  until?: number;
}

/** A list older than this isn't shown even while a new read runs. */
export const MAX_STALE_MS = 12 * 60 * 60_000;

/**
 * What to do with a held read: answer from it ('fresh'), answer from it while
 * a new read runs ('usable': it has accounts, just old ones), or read again
 * first ('expired'). An empty list is never used stale: that was a failure,
 * and serving it is what kept people signed out after one bad read.
 */
export function freshness(held: HeldRead, now = Date.now()): 'fresh' | 'usable' | 'expired' {
  const until = held.until ?? (held.list.length ? held.at + COMPLETE_TTL_MS : held.at);
  if (now < until) return 'fresh';
  return held.list.length && now - held.at < MAX_STALE_MS ? 'usable' : 'expired';
}

/** How long a read's result counts as fresh. */
export function ttlFor(result: { list: readonly unknown[]; complete: boolean; retryAfterMs?: number | null }): number {
  if (result.complete) return result.list.length ? COMPLETE_TTL_MS : EMPTY_TTL_MS;
  if (result.list.length) return PARTIAL_TTL_MS;
  return Math.min(60_000, Math.max(FAILED_TTL_MS, result.retryAfterMs ?? 0));
}
