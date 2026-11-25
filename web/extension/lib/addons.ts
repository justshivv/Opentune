import { publicHttps } from '@opentune/shared/addons';
import type { HttpRequest, HttpResponse } from '@opentune/shared/bridge';
import { isReservedHost } from './hostPolicy';
import { fetchWithDeadline, MB, readBounded } from './http';
import { openExtensionPage } from './pages';

const MAX_RESPONSE = 2 * MB;
const TIMEOUT_MS = 8_000;
const MAX_HOSTS = 40;

function forbidden(message: string): Error {
  return Object.assign(new Error(message), { code: 'forbidden' });
}

/** The add-on origin for [raw]: public HTTPS, and not one of the hosts the extension holds sessions for. */
function addonOrigin(raw: string): URL {
  let url: URL;
  try {
    url = publicHttps(raw);
  } catch (error) {
    throw forbidden(error instanceof Error ? error.message : 'Use a public HTTPS URL');
  }
  if (isReservedHost(url.hostname)) throw forbidden(`${url.host} can't be used as an audio add-on host`);
  return url;
}

/** One page that asks for all of these add-on hosts at once. */
export async function requestAddonPermission(url: string, urls: string[] = []): Promise<void> {
  const origins = [...new Set([url, ...urls].map((u) => addonOrigin(u).origin))].slice(0, MAX_HOSTS);
  await openExtensionPage(`/grant.html?kind=addons&origins=${encodeURIComponent(origins.join(','))}`);
}

/**
 * A GET to an audio add-on the user allowed: no custom headers, no cookies,
 * no referrer, no redirects, eight seconds and two megabytes at most.
 */
export async function addonFetch(request: HttpRequest): Promise<HttpResponse> {
  const url = addonOrigin(request.url);
  if ((request.method && request.method !== 'GET') || request.body || (request.headers && Object.keys(request.headers).length)) {
    throw forbidden('Add-on requests are plain GETs without custom headers');
  }
  if (!(await browser.permissions.contains({ origins: [`${url.origin}/*`] }))) {
    throw Object.assign(new Error('Allow this add-on host from Audio add-ons first'), { code: 'permission' });
  }
  const response = await fetchWithDeadline(url, {
    credentials: 'omit',
    redirect: 'error',
    referrerPolicy: 'no-referrer',
    timeoutMs: TIMEOUT_MS,
  });
  if (!response.body) throw new Error('Empty add-on response');
  const body = await readBounded(response, MAX_RESPONSE);
  return {
    status: response.status,
    headers: { 'retry-after': response.headers.get('retry-after') ?? '' },
    body: new TextDecoder().decode(body),
    bodyEncoding: 'text',
    url: url.href,
  };
}
