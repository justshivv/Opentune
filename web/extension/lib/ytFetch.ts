import type { HttpResponse, YtFetchParams } from '@opentune/shared/bridge';
import { fetchWithDeadline, fromBase64, MB, serialise } from './http';
import { ensureExtensionFetchRule } from './rules';
import { activeIdentity, signedFetch, SignedOutError } from './ytAuth';
import { ALLOWED, isAllowedSigned, isStatsPing } from './ytUrls';

const MUSIC_ORIGIN = 'https://music.youtube.com';
/** Identity is the extension's to set; the page's copies of these are dropped. */
const IDENTITY_HEADERS = ['authorization', 'cookie', 'x-goog-authuser', 'x-goog-pageid', 'x-origin', 'origin', 'referer'];

/** InnerTube answers (a browse page, a long queue) run to a few MB at most. */
const MAX_RESPONSE = 16 * MB;
/** Just under the page's default deadline for a bridge call (30 s). */
const TIMEOUT_MS = 25_000;

function forbidden(url: string): Error {
  const { origin, pathname } = new URL(url);
  return Object.assign(new Error(`yt.fetch doesn't serve ${origin}${pathname}`), { code: 'forbidden' });
}

/** The page's body as fetch takes it: text as-is, base64 decoded. */
function bodyOf(request: YtFetchParams): BodyInit | undefined {
  if (request.body === undefined) return undefined;
  return request.bodyEncoding === 'base64' ? (fromBase64(request.body) as Uint8Array<ArrayBuffer>) : request.body;
}

function pageHeaders(headers: Record<string, string> | undefined): Record<string, string> {
  const kept: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers ?? {})) {
    if (!IDENTITY_HEADERS.includes(key.toLowerCase())) kept[key] = value;
  }
  return kept;
}

/**
 * A request to YouTube from the extension, with YouTube's Origin and Referer
 * (rules.ts, installExtensionFetchRule).
 *
 * - Anonymous by default: no cookies.
 * - With `auth`, made as the chosen channel (ytAuth.ts). The page gets the
 *   response, as it would from YouTube's own site, but never the cookies or
 *   the signature that produced it.
 *
 * Either way: 25 s and 16 MB at most, and no redirects followed.
 */
export async function ytFetch(request: YtFetchParams): Promise<HttpResponse> {
  if (request.auth) return signedYtFetch(request);
  if (!ALLOWED.some((pattern) => pattern.test(request.url))) throw forbidden(request.url);
  await ensureExtensionFetchRule();
  const response = await fetchWithDeadline(request.url, {
    method: request.method ?? 'GET',
    headers: pageHeaders(request.headers),
    body: bodyOf(request),
    credentials: 'omit',
    redirect: 'error',
    timeoutMs: TIMEOUT_MS,
  });
  return serialise(response, MAX_RESPONSE);
}

async function signedYtFetch(request: YtFetchParams): Promise<HttpResponse> {
  const stats = isStatsPing(request.url, request.method);
  if (!isAllowedSigned(request.url, request.method)) throw forbidden(request.url);
  const identity = await activeIdentity();
  if (!identity) throw new SignedOutError('Not signed in to YouTube Music.');
  const response = await signedFetch(
    request.url,
    {
      method: request.method ?? 'GET',
      headers: pageHeaders(request.headers),
      body: bodyOf(request),
      // A ping only has to arrive; one that redirects is left there, not followed.
      redirect: stats ? 'manual' : 'error',
      timeoutMs: TIMEOUT_MS,
    },
    identity,
    // A stats ping is signed for YouTube Music, whichever host it goes to, as the web client's are.
    stats ? MUSIC_ORIGIN : undefined,
  );
  return serialise(response, MAX_RESPONSE, request.url);
}
