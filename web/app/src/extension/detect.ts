import { BridgeCallError, externalBridge, relayBridge, type Bridge, type DetectedExtension } from '@opentune/shared/bridge';

/**
 * Finding the extension when the page opens. An MV3 service worker that has
 * gone to sleep is woken by the first message and can take a moment to
 * answer; on Firefox the relay content script may still be starting. So the
 * first contact isn't one ping with one deadline: pings go out at 0, 100,
 * 350, 850 ms and so on, each left open until the overall deadline, and the
 * first answer wins. An error such as "Receiving end does not exist" only
 * ends that one ping; a later one still can be answered.
 */

/** The pauses between pings. */
export const PING_GAPS_MS: readonly number[] = [100, 250, 500, 1000, 1500, 2000, 2500];
/** Someone who has never had the extension here: the setup popup can't wait long. */
export const NEW_VISITOR_BUDGET_MS = 2500;
/** The extension answered on this browser before: it gets longer to wake before it's called silent. */
export const KNOWN_BUDGET_MS = 8000;

export type Probe =
  | { kind: 'found'; extension: DetectedExtension }
  /** The extension answered, but no: it refused this page, or speaks another protocol. */
  | { kind: 'refused'; code: string; message: string }
  /** Nothing answered within the budget. */
  | { kind: 'silent' };

/** One ping over every transport this page has; resolves with the first answer, rejects when all fail. */
export type Ping = (timeoutMs: number) => Promise<DetectedExtension>;

export interface FindOptions {
  budgetMs: number;
  ping: Ping;
  gaps?: readonly number[];
  wait?: (ms: number) => Promise<void>;
  now?: () => number;
}

/** A refusal is an answer: the extension is there, so waiting longer changes nothing. */
function refusalOf(error: unknown): { code: string; message: string } | null {
  const errors = error instanceof AggregateError ? error.errors : [error];
  for (const e of errors) {
    if (e instanceof BridgeCallError && (e.code === 'forbidden' || e.code === 'protocol' || e.code === 'unsupported')) {
      return { code: e.code, message: e.message };
    }
  }
  return null;
}

const pause = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export function findExtension({ budgetMs, ping, gaps = PING_GAPS_MS, wait = pause, now = Date.now }: FindOptions): Promise<Probe> {
  return new Promise<Probe>((resolve) => {
    const start = now();
    let outstanding = 0;
    let launching = true;
    let done = false;
    const finish = (probe: Probe) => {
      if (done) return;
      done = true;
      resolve(probe);
    };
    const maybeSilent = () => {
      if (!launching && outstanding === 0) finish({ kind: 'silent' });
    };
    const launch = () => {
      const remaining = budgetMs - (now() - start);
      if (remaining <= 0) return;
      outstanding++;
      ping(remaining)
        .then(
          (extension) => finish({ kind: 'found', extension }),
          (error: unknown) => {
            const refused = refusalOf(error);
            if (refused) finish({ kind: 'refused', ...refused });
          },
        )
        .finally(() => {
          outstanding--;
          maybeSilent();
        });
    };
    void (async () => {
      launch();
      for (const gap of gaps) {
        const remaining = budgetMs - (now() - start);
        if (done || remaining <= 0) break;
        await wait(Math.min(gap, remaining));
        if (done || now() - start >= budgetMs) break;
        launch();
      }
      launching = false;
      maybeSilent();
    })();
  });
}

/** The transports this page has: Chromium's direct one when it's exposed, and the Firefox relay. */
function transports(chromiumExtensionId: string | undefined): Bridge[] {
  const list: Bridge[] = [];
  const external = chromiumExtensionId ? externalBridge(chromiumExtensionId) : null;
  if (external) list.push(external);
  if (typeof window !== 'undefined') list.push(relayBridge(window));
  return list;
}

/** A ping over every transport at once (only one can be live), the first answer winning. */
export function pingOver(chromiumExtensionId: string | undefined): Ping {
  return (timeoutMs) =>
    Promise.any(
      transports(chromiumExtensionId).map((bridge) =>
        bridge.call('ping', {}, { timeoutMs }).then((info) => ({ bridge, info }) satisfies DetectedExtension),
      ),
    );
}

// ---- "Seen before" ---------------------------------------------------------------

const SEEN_KEY = 'neptune:extension-seen';
/** Set by the setup popup when it was finished (before this key existed): that visitor had the extension too. */
const LEGACY_DONE_KEY = 'opentune:allow-gate-done';

export interface SeenExtension {
  version: string;
  browser: string;
  at: number;
}

/** The extension that last answered on this browser, if any. Names and versions only. */
export function readSeen(): SeenExtension | null {
  try {
    const raw = localStorage.getItem(SEEN_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<SeenExtension>;
      if (typeof parsed.version === 'string') return { version: parsed.version, browser: String(parsed.browser ?? ''), at: Number(parsed.at) || 0 };
    }
    if (localStorage.getItem(LEGACY_DONE_KEY) === '1') return { version: '', browser: '', at: 0 };
  } catch {
    /* Storage blocked: treated as a first visit. */
  }
  return null;
}

export function rememberSeen(info: { version: string; browser: string }): void {
  try {
    localStorage.setItem(SEEN_KEY, JSON.stringify({ version: info.version, browser: info.browser, at: Date.now() }));
  } catch {
    /* Asked the long way next time. */
  }
}
