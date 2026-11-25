import type { HttpRequest, HttpResponse } from '@opentune/shared/bridge';

import { lyricHeaders } from './hostPolicy';
import { fetchWithDeadline, MB, serialise } from './http';
import { LYRIC_ORIGINS, patternOf } from './lyricHosts';
import { openExtensionPage } from './pages';

export { LYRIC_ORIGINS, patternOf };

export class LyricsPermissionError extends Error {
  readonly code = 'permission';
}

/** Apple Music's and Musixmatch's pages and scripts are read for their tokens; those run to a few MB. */
const MAX_RESPONSE = 16 * MB;
/** Just under the page's own deadline for a lyric call (15 s), so the page hears why rather than a timeout. */
const TIMEOUT_MS = 14_000;

const isLyricOrigin = (origin: string) => (LYRIC_ORIGINS as readonly string[]).includes(origin);

/** Headers some hosts need that a fetch may not set, applied to the extension's own requests (tabId -1). */
const RULES = [
  {
    id: 200,
    urlFilter: '||amp-api.music.apple.com/',
    headers: [
      { header: 'Origin', operation: 'set', value: 'https://music.apple.com' },
      { header: 'Referer', operation: 'set', value: 'https://music.apple.com/' },
    ],
  },
  {
    // Musixmatch's web client and the signing key it carries (Musixmatch.kt's browserGet).
    id: 201,
    urlFilter: '||musixmatch.com/',
    headers: [{ header: 'Cookie', operation: 'set', value: 'mxm_bab=AB' }],
  },
] as const;

let rulesReady: Promise<void> | null = null;

function installRules(): Promise<void> {
  rulesReady ??= browser.declarativeNetRequest
    .updateSessionRules({
      removeRuleIds: RULES.map((r) => r.id),
      addRules: RULES.map((r) => ({
        id: r.id,
        priority: 1,
        condition: { urlFilter: r.urlFilter, tabIds: [-1], resourceTypes: ['xmlhttprequest'] },
        action: { type: 'modifyHeaders', requestHeaders: r.headers.map((h) => ({ ...h })) },
      })) as Browser.declarativeNetRequest.Rule[],
    })
    .catch((error: unknown) => {
      rulesReady = null;
      throw error;
    });
  return rulesReady;
}

/** Which of [origins] the user has granted. */
export async function grantedOrigins(origins: string[]): Promise<string[]> {
  const known = [...new Set(origins)].filter(isLyricOrigin);
  const answers = await Promise.all(known.map((o) => browser.permissions.contains({ origins: [patternOf(o)] })));
  return known.filter((_, i) => answers[i]);
}

/** Opens the extension's page that asks for [origins]; the browser only prompts after a click there. */
export async function requestOrigins(origins: string[]): Promise<void> {
  const known = [...new Set(origins)].filter(isLyricOrigin);
  if (!known.length) return;
  await openExtensionPage(`/grant.html?origins=${encodeURIComponent(known.join(','))}`);
}

/**
 * A request to a lyric provider the user allowed: no cookies, only the
 * headers lyric hosts need, 14 s and 16 MB at most, and redirects only
 * between lyric hosts.
 */
export async function lyricsFetch(request: HttpRequest): Promise<HttpResponse> {
  const url = new URL(request.url);
  if (url.protocol !== 'https:' || !isLyricOrigin(url.origin)) {
    throw Object.assign(new Error(`lyrics.fetch doesn't serve ${url.origin}`), { code: 'forbidden' });
  }
  if (!(await browser.permissions.contains({ origins: [patternOf(url.origin)] }))) {
    throw new LyricsPermissionError(`NepTune needs permission to reach ${url.host} for lyrics.`);
  }
  await installRules();
  const response = await fetchWithDeadline(url, {
    method: request.method ?? 'GET',
    headers: lyricHeaders(request.headers),
    ...(request.body !== undefined && { body: request.body }),
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    timeoutMs: TIMEOUT_MS,
  });
  // A redirect may move within a provider's hosts, never off them.
  const landed = response.url ? new URL(response.url).origin : url.origin;
  if (!isLyricOrigin(landed)) {
    await response.body?.cancel().catch(() => undefined);
    throw Object.assign(new Error(`${url.host} redirected to ${new URL(response.url).host}, which isn't a lyric source`), {
      code: 'forbidden',
    });
  }
  return serialise(response, MAX_RESPONSE);
}
