import { isPlayerUrl } from './site.ts';

/** The parts of a runtime.MessageSender the checks read. */
export interface SenderLike {
  id?: string | undefined;
  origin?: string | undefined;
  url?: string | undefined;
}

function urlOrigin(url: string | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

/** The sender's origin, for messages: the browser's own `origin` when it gives one, else the URL's. */
export function senderOrigin(sender: SenderLike): string | null {
  return sender.origin ?? urlOrigin(sender.url);
}

/**
 * One of this extension's own pages (the popup, grant.html, discord.html).
 * [ownBase] is `runtime.getURL('/')`. Compared as a URL prefix rather than
 * through `URL.origin`, which some runtimes report as "null" for extension
 * schemes.
 */
export function isOwnPage(sender: SenderLike, ownBase: string, ownId: string): boolean {
  if (!ownId || sender.id !== ownId || !ownBase.endsWith('/')) return false;
  if (!sender.url?.startsWith(ownBase)) return false;
  // Firefox leaves `origin` out; Chromium gives both, and they must agree.
  return sender.origin === undefined || `${sender.origin}/` === ownBase;
}

/**
 * A page of the web player: its URL is on a site origin and, on GitHub Pages,
 * under the player's path. When the browser also reports an origin (Chromium,
 * for externally_connectable), it must be the URL's own: an opaque or
 * inherited origin (sandboxed or srcdoc frames, blob: URLs) is refused.
 */
export function isPlayerSender(sender: SenderLike, siteOrigins: readonly string[]): boolean {
  if (!isPlayerUrl(sender.url, siteOrigins)) return false;
  return sender.origin === undefined || sender.origin === urlOrigin(sender.url);
}
