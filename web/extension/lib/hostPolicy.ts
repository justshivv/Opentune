/**
 * Which hosts the page may send through the extension, beyond its fixed
 * routes. Pure, so it runs under `node --test`.
 */

/**
 * Hosts the extension holds sessions, header rules or install-time access
 * for. `addon.fetch` is for the user's own audio add-ons; it must not become
 * a way to reach these with the extension's privileges, so they're refused
 * there (and never offered on the add-on grant page), whatever the
 * permissions say.
 */
const RESERVED = ['youtube.com', 'googlevideo.com', 'spotify.com', 'spotifycdn.com', 'discord.com'];

/** Whether [hostname] is, or is under, one of the reserved domains. */
export function isReservedHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/\.$/, '');
  return RESERVED.some((domain) => host === domain || host.endsWith(`.${domain}`));
}

/** Headers a lyric request may set; anything else (Cookie, Origin, Referer, Host…) is the extension's to decide. */
const LYRIC_HEADERS = new Set(['accept', 'accept-language', 'authorization', 'content-type']);

/** The page's headers for a lyric request, keeping only the ones it may set. */
export function lyricHeaders(headers: Record<string, string> | undefined): Record<string, string> {
  const kept: Record<string, string> = {};
  for (const [name, value] of Object.entries(headers ?? {})) {
    if (LYRIC_HEADERS.has(name.toLowerCase())) kept[name] = value;
  }
  return kept;
}
