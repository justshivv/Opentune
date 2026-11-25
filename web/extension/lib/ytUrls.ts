/**
 * Where `yt.fetch` may send a request. Pure, so the rules are tested under
 * `node --test` (ytUrls.test.ts) against what the web player really sends.
 */

/** Where an anonymous `yt.fetch` may go: BotGuard, which a page can't call with YouTube's Origin. */
export const ALLOWED = [/^https:\/\/www\.youtube\.com\/api\/jnn\//];
/** Where a signed one may go: InnerTube only, never an account or settings page. */
export const ALLOWED_SIGNED = [/^https:\/\/(music|www)\.youtube\.com\/youtubei\/v1\//];
/**
 * The play-reporting pings a player response nominates (playbackTracking):
 * playback, watchtime and atr, GET only. They're what puts a play in the
 * account's YouTube Music history (PlaybackTracker.kt on the phone).
 */
export const STATS = /^https:\/\/(s|music|www)\.youtube\.com\/api\/stats\/(playback|watchtime|atr)(\?|$)/;

/** A play-reporting ping: one of the STATS URLs, GET only. */
export function isStatsPing(url: string, method: string | undefined): boolean {
  return STATS.test(url) && (method ?? 'GET') === 'GET';
}

/** Whether a signed `yt.fetch` to [url] is allowed at all (InnerTube, or a stats ping). */
export function isAllowedSigned(url: string, method: string | undefined): boolean {
  return isStatsPing(url, method) || ALLOWED_SIGNED.some((pattern) => pattern.test(url));
}
