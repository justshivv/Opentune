import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  COMPLETE_TTL_MS,
  EMPTY_TTL_MS,
  FAILED_TTL_MS,
  freshness,
  HttpStatusError,
  isTransient,
  mapPool,
  mergeLogins,
  PARTIAL_TTL_MS,
  parseRetryAfter,
  ttlFor,
  withRetry,
  type LoginOutcome,
} from './accountRead.ts';
import { NetworkError } from './http.ts';

const account = (authUser: string, email: string) => ({ authUser, email, name: email.split('@')[0]!, channels: [{ id: `${authUser}:own` }] });
type Account = ReturnType<typeof account>;

describe('isTransient', () => {
  it('counts rate limits, server errors and lost requests as passing', () => {
    assert.equal(isTransient(new HttpStatusError(429)), true);
    assert.equal(isTransient(new HttpStatusError(503)), true);
    assert.equal(isTransient(new NetworkError('music.youtube.com didn’t answer within 15 s')), true);
  });

  it('counts a refusal as an answer', () => {
    assert.equal(isTransient(new HttpStatusError(403)), false);
    assert.equal(isTransient(new HttpStatusError(401)), false);
    assert.equal(isTransient(new Error('The browser is not signed in to YouTube.')), false);
  });
});

describe('parseRetryAfter', () => {
  it('reads seconds and HTTP dates', () => {
    assert.equal(parseRetryAfter('3'), 3000);
    const now = Date.parse('2026-01-01T00:00:00Z');
    assert.equal(parseRetryAfter('Thu, 01 Jan 2026 00:00:05 GMT', now), 5000);
    assert.equal(parseRetryAfter(null), null);
    assert.equal(parseRetryAfter('soon'), null);
  });
});

describe('withRetry', () => {
  it('waits and tries again after a passing failure, honouring Retry-After', async () => {
    const waits: number[] = [];
    let calls = 0;
    const value = await withRetry(
      async () => {
        calls++;
        if (calls === 1) throw new HttpStatusError(429, 2500);
        return 'ok';
      },
      { sleep: async (ms) => void waits.push(ms) },
    );
    assert.equal(value, 'ok');
    assert.deepEqual(waits, [2500]);
  });

  it('backs off when no wait was given', async () => {
    const waits: number[] = [];
    await assert.rejects(
      withRetry(
        async () => {
          throw new NetworkError('timeout');
        },
        { attempts: 3, baseMs: 100, sleep: async (ms) => void waits.push(ms) },
      ),
      NetworkError,
    );
    assert.deepEqual(waits, [100, 200]);
  });

  it('gives up at once on a refusal, a wait that is too long, or no time left', async () => {
    let calls = 0;
    const refuse = async () => {
      calls++;
      throw new HttpStatusError(403);
    };
    await assert.rejects(withRetry(refuse, { sleep: async () => undefined }), HttpStatusError);
    assert.equal(calls, 1);

    const slow = async () => {
      throw new HttpStatusError(429, 60_000);
    };
    await assert.rejects(withRetry(slow, { sleep: async () => assert.fail('should not wait') }), HttpStatusError);

    const late = async () => {
      throw new NetworkError('timeout');
    };
    await assert.rejects(withRetry(late, { deadline: Date.now() + 10, baseMs: 500, sleep: async () => assert.fail('should not wait') }));
  });
});

describe('mapPool', () => {
  it('keeps to the limit and the order', async () => {
    let running = 0;
    let most = 0;
    const out = await mapPool([1, 2, 3, 4, 5, 6, 7], 3, async (n) => {
      running++;
      most = Math.max(most, running);
      await new Promise((r) => setTimeout(r, 5 - (n % 3)));
      running--;
      return n * 10;
    });
    assert.deepEqual(out, [10, 20, 30, 40, 50, 60, 70]);
    assert.equal(most, 3);
  });
});

describe('mergeLogins', () => {
  it('keeps what answered when another login failed, and fills the gap from the last read', () => {
    const outcomes: LoginOutcome<Account>[] = [
      { kind: 'account', account: account('0', 'a@x') },
      { kind: 'failed', why: 'account_menu: HTTP 429', retryAfterMs: 4000 },
      { kind: 'none', why: 'HTTP 403' },
    ];
    const merged = mergeLogins(outcomes, [account('0', 'a@x'), account('1', 'b@x')]);
    assert.deepEqual(
      merged.list.map((a) => a.email),
      ['a@x', 'b@x'],
    );
    assert.equal(merged.complete, false);
    assert.equal(merged.failedWhy, 'account_menu: HTTP 429');
    assert.equal(merged.retryAfterMs, 4000);
  });

  it('is partial rather than empty when one index of several fails without a previous read', () => {
    const merged = mergeLogins<Account>(
      [
        { kind: 'failed', why: 'timeout', retryAfterMs: null },
        { kind: 'account', account: account('1', 'b@x') },
      ],
      null,
    );
    assert.deepEqual(
      merged.list.map((a) => a.email),
      ['b@x'],
    );
    assert.equal(merged.complete, false);
  });

  it('drops a login seen twice, and a failure the last read had nothing for', () => {
    const merged = mergeLogins<Account>(
      [
        { kind: 'account', account: account('0', 'a@x') },
        { kind: 'account', account: account('1', 'a@x') },
        { kind: 'none', why: 'no' },
      ],
      null,
    );
    assert.equal(merged.list.length, 1);
    assert.equal(merged.complete, true);
  });
});

describe('caching', () => {
  it('keeps a failure for seconds, a partial read for a minute and a full read for ten', () => {
    assert.equal(ttlFor({ list: [1], complete: true }), COMPLETE_TTL_MS);
    assert.equal(ttlFor({ list: [], complete: true }), EMPTY_TTL_MS);
    assert.equal(ttlFor({ list: [1], complete: false }), PARTIAL_TTL_MS);
    assert.equal(ttlFor({ list: [], complete: false }), FAILED_TTL_MS);
    assert.equal(ttlFor({ list: [], complete: false, retryAfterMs: 30_000 }), 30_000);
  });

  it('answers from an old list while reading again, but never from an old failure', () => {
    const now = 1_000_000_000;
    assert.equal(freshness({ list: [1], at: now - 1000, until: now + 1000 }, now), 'fresh');
    assert.equal(freshness({ list: [1], at: now - 20 * 60_000, until: now - 10 * 60_000 }, now), 'usable');
    assert.equal(freshness({ list: [], at: now - 20_000, until: now - 5000 }, now), 'expired');
    // What 0.2.0 stored: no `until`. Its failures (empty lists) were held ten minutes; not any more.
    assert.equal(freshness({ list: [], at: now - 1000 }, now), 'expired');
    assert.equal(freshness({ list: [1], at: now - 1000 }, now), 'fresh');
  });
});
