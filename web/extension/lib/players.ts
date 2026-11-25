/**
 * Which player tabs have talked to the extension, for the popup's "connected"
 * line. The worker can be stopped at any moment, so the record lives in
 * `storage.session` (memory only, cleared when the browser or the extension
 * restarts), written at most once a minute per tab. Ports are held only while
 * the worker lives, which is fine: a dropped port reconnects on its own.
 */

const KEY = 'players.seen';
/** Writes are skipped while the stored time for the tab is fresher than this. */
const WRITE_EVERY_MS = 60_000;
/** Entries kept at most; the oldest go first. */
const MAX_ENTRIES = 32;

/** tabId → when that tab last sent a request or opened a port. */
type Seen = Record<string, number>;

const written = new Map<number, number>();
const portTabs = new Map<number, number>();
let writing: Promise<void> = Promise.resolve();

async function read(): Promise<Seen> {
  try {
    return ((await browser.storage.session.get(KEY))[KEY] as Seen | undefined) ?? {};
  } catch {
    return {};
  }
}

/** Notes that [tabId] reached the extension; cheap when called on every request. */
export function noteContact(tabId: number | undefined): void {
  if (tabId === undefined || tabId < 0) return;
  const now = Date.now();
  if (now - (written.get(tabId) ?? 0) < WRITE_EVERY_MS) return;
  written.set(tabId, now);
  // One read-modify-write at a time, so two tabs noted together both stay.
  writing = writing
    .then(read)
    .then((seen) => {
      seen[tabId] = now;
      const entries = Object.entries(seen).sort((a, b) => b[1] - a[1]).slice(0, MAX_ENTRIES);
      return browser.storage.session.set({ [KEY]: Object.fromEntries(entries) });
    })
    .catch(() => undefined);
}

export function portOpened(tabId: number | undefined): void {
  if (tabId === undefined) return;
  portTabs.set(tabId, (portTabs.get(tabId) ?? 0) + 1);
  noteContact(tabId);
}

export function portClosed(tabId: number | undefined): void {
  if (tabId === undefined) return;
  const left = (portTabs.get(tabId) ?? 1) - 1;
  if (left > 0) portTabs.set(tabId, left);
  else portTabs.delete(tabId);
}

export interface PlayerContact {
  tabId: number;
  /** Last request or port, ms since the epoch; null when the tab hasn't been heard from this session. */
  lastSeen: number | null;
  /** It holds an event port right now. */
  live: boolean;
}

/** What the popup shows for each of [tabIds]. */
export async function contacts(tabIds: number[]): Promise<PlayerContact[]> {
  const seen = await read();
  return tabIds.map((tabId) => ({ tabId, lastSeen: seen[tabId] ?? null, live: portTabs.has(tabId) }));
}
