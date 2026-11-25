/**
 * Where the web player lives, and how a sender's URL is matched against it.
 * Pure: shared by the manifest (wxt.config.ts, through site.config.ts), the
 * background's sender check and the popup's tab lookup, so all three agree.
 */

/** GitHub Pages serves the player under one repository path; nothing else on the host may use the bridge. */
export const PRODUCTION_ORIGIN = 'https://justshivv.github.io';
export const PRODUCTION_PATH = '/Opentune/player/';

/** Manifest match patterns for the site origins (externally_connectable, the Firefox relay, host access). */
export function matchPatternsFor(origins: readonly string[]): string[] {
  return origins.map((o) => (o === PRODUCTION_ORIGIN ? `${o}${PRODUCTION_PATH}*` : `${new URL(o).protocol}//${new URL(o).hostname}/*`));
}

/**
 * Whether [url] is a page of the player: an exact site origin (port included)
 * and, on the production host, the player's own path. Anything that isn't a
 * parseable http(s) URL is not.
 */
export function isPlayerUrl(url: string | undefined | null, origins: readonly string[]): boolean {
  if (!url) return false;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return false;
  if (parsed.username || parsed.password) return false;
  if (!origins.includes(parsed.origin)) return false;
  return parsed.origin !== PRODUCTION_ORIGIN || parsed.pathname.startsWith(PRODUCTION_PATH);
}
