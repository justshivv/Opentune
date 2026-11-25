// @vitest-environment happy-dom
import { BridgeCallError, type DetectedExtension } from '@opentune/shared/bridge';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { findExtension, KNOWN_BUDGET_MS, NEW_VISITOR_BUDGET_MS, readSeen, rememberSeen, type Ping } from './detect';

const extension = { bridge: {}, info: { version: '0.2.1', protocol: 1, browser: 'chromium', capabilities: [] } } as unknown as DetectedExtension;

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
});
afterEach(() => vi.useRealTimers());

/** A ping that answers after [ms], or fails at once with [error]. */
const answersAfter = (ms: number) => () => new Promise<DetectedExtension>((resolve) => setTimeout(() => resolve(extension), ms));
const failsWith = (error: unknown) => () => Promise.reject(error);

/** Runs findExtension under fake time; returns what it found and when. */
async function run(ping: Ping, budgetMs: number) {
  const start = Date.now();
  let settled: { probe: Awaited<ReturnType<typeof findExtension>>; at: number } | null = null;
  void findExtension({ budgetMs, ping }).then((probe) => (settled = { probe, at: Date.now() - start }));
  for (let i = 0; i < 200 && !settled; i++) await vi.advanceTimersByTimeAsync(50);
  return settled!;
}

it('keeps pinging a waking worker: errors before it is up are "not yet", not "missing"', async () => {
  const sent: number[] = [];
  const start = Date.now();
  const ping: Ping = (timeoutMs) => {
    sent.push(Date.now() - start);
    // The worker is up from 400 ms; before that Chrome says nobody is listening.
    if (Date.now() - start < 400) return failsWith(new BridgeCallError('unavailable', 'Receiving end does not exist.'))();
    expect(timeoutMs).toBeGreaterThan(0);
    return answersAfter(20)();
  };
  const { probe, at } = await run(ping, NEW_VISITOR_BUDGET_MS);
  expect(probe.kind).toBe('found');
  // Pinged at 0, 100 and 350 (failed), then 850: found well before the budget.
  expect(sent.slice(0, 4)).toEqual([0, 100, 350, 850]);
  expect(at).toBeLessThan(1000);
});

it('takes a slow first answer: an early ping stays open until the deadline', async () => {
  let calls = 0;
  const ping: Ping = () => (++calls === 1 ? answersAfter(1800)() : new Promise<DetectedExtension>(() => {}));
  const { probe, at } = await run(ping, NEW_VISITOR_BUDGET_MS);
  expect(probe.kind).toBe('found');
  expect(at).toBeGreaterThanOrEqual(1800);
  expect(at).toBeLessThan(1900);
});

it('reports silence only once the budget is spent', async () => {
  const neverAnswers: Ping = (timeoutMs) =>
    new Promise((_, reject) => setTimeout(() => reject(new BridgeCallError('timeout', 'no answer')), timeoutMs));
  const quick = await run(neverAnswers, NEW_VISITOR_BUDGET_MS);
  expect(quick.probe.kind).toBe('silent');
  expect(quick.at).toBeGreaterThanOrEqual(NEW_VISITOR_BUDGET_MS);
  expect(quick.at).toBeLessThan(NEW_VISITOR_BUDGET_MS + 200);

  const patient = await run(neverAnswers, KNOWN_BUDGET_MS);
  expect(patient.probe.kind).toBe('silent');
  expect(patient.at).toBeGreaterThanOrEqual(KNOWN_BUDGET_MS);
});

it('stops at a refusal: the extension answered, waiting changes nothing', async () => {
  const { probe, at } = await run(failsWith(new AggregateError([new BridgeCallError('protocol', 'speaks 2')])), KNOWN_BUDGET_MS);
  expect(probe).toEqual({ kind: 'refused', code: 'protocol', message: 'speaks 2' });
  expect(at).toBeLessThan(100);
});

it('remembers the extension it saw, and counts a finished setup from before as seen', () => {
  expect(readSeen()).toBeNull();
  rememberSeen({ version: '0.2.1', browser: 'firefox' });
  expect(readSeen()).toMatchObject({ version: '0.2.1', browser: 'firefox' });

  localStorage.clear();
  localStorage.setItem('opentune:allow-gate-done', '1');
  expect(readSeen()).not.toBeNull();
});
