/**
 * Opens one of the extension's own pages (grant.html) for the page. The web
 * player can ask for one at any time, without a click, so this keeps it from
 * piling up tabs: an identical page that's already open is brought forward
 * instead (where the browser can list the extension's tabs), and repeats
 * within a few seconds are dropped.
 */

const REPEAT_MS = 3_000;
const recent = new Map<string, number>();

type Context = { tabId: number; windowId: number; documentUrl?: string };
type GetContexts = (filter: { contextTypes: string[] }) => Promise<Context[]>;

async function openTabFor(url: string): Promise<Context | null> {
  // runtime.getContexts: Chrome 116+, Firefox doesn't have it yet.
  const getContexts = (browser.runtime as unknown as { getContexts?: GetContexts }).getContexts;
  if (typeof getContexts !== 'function') return null;
  try {
    const tabs = await getContexts.call(browser.runtime, { contextTypes: ['TAB'] });
    return tabs.find((c) => c.documentUrl === url && c.tabId >= 0) ?? null;
  } catch {
    return null;
  }
}

export async function openExtensionPage(path: string): Promise<void> {
  const url = browser.runtime.getURL(path as '/grant.html');
  const now = Date.now();
  if (now - (recent.get(url) ?? 0) < REPEAT_MS) return;
  recent.set(url, now);
  for (const [key, at] of recent) if (now - at > REPEAT_MS) recent.delete(key);

  const open = await openTabFor(url);
  if (open) {
    await browser.tabs.update(open.tabId, { active: true });
    await browser.windows.update(open.windowId, { focused: true }).catch(() => undefined);
    return;
  }
  await browser.tabs.create({ url });
}
