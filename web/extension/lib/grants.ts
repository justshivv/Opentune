/**
 * The optional host access the user has granted, sorted for the popup:
 * lyric providers and audio add-on hosts. Pure, so it runs under
 * `node --test`.
 */

export interface Grants {
  /** Granted lyric origins, in LYRIC_ORIGINS order. */
  lyrics: string[];
  /** Other granted https hosts (audio add-ons), as origins, sorted. */
  addons: string[];
}

/** `https://host/*` → `https://host`; null for a pattern that isn't one host's. */
export function originOfPattern(pattern: string): string | null {
  const match = /^(https?):\/\/([^/*]+)\/\*$/.exec(pattern);
  return match ? `${match[1]}://${match[2]}` : null;
}

/**
 * Whether [pattern]'s host covers [origin]. Paths are ignored, as browsers
 * ignore them for host access (Chrome reports `…/Opentune/player/*` back as
 * `…/*`), and so are ports.
 */
export function patternCovers(pattern: string, origin: string): boolean {
  const match = /^(\*|https?):\/\/([^/]+)\//.exec(pattern);
  if (!match) return false;
  let url: URL;
  try {
    url = new URL(origin);
  } catch {
    return false;
  }
  const [, scheme, host] = match as unknown as [string, string, string];
  if (scheme !== '*' && `${scheme}:` !== url.protocol) return false;
  if (host === '*') return true;
  if (host.startsWith('*.')) return url.hostname === host.slice(2) || url.hostname.endsWith(host.slice(1));
  return url.hostname === host.replace(/:\d+$/, '');
}

/**
 * Sorts [granted] (from `permissions.getAll().origins`) into lyric hosts and
 * add-on hosts. Hosts the install already covers ([required]) and [ignored]
 * patterns (the Discord connect page's) are left out: they aren't the user's
 * to revoke here.
 */
export function sortGrants(
  granted: readonly string[],
  required: readonly string[],
  lyricOrigins: readonly string[],
  ignored: readonly string[] = [],
): Grants {
  const origins = new Set(
    granted
      .filter((p) => !ignored.includes(p))
      .map(originOfPattern)
      .filter((o): o is string => o !== null && !required.some((r) => patternCovers(r, o))),
  );
  const lyrics = lyricOrigins.filter((o) => origins.has(o));
  const addons = [...origins].filter((o) => !lyricOrigins.includes(o) && o.startsWith('https://')).sort();
  return { lyrics, addons };
}
