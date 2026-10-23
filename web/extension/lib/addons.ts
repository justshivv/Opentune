import { publicHttps } from '@opentune/shared/addons';
import type { HttpRequest, HttpResponse } from '@opentune/shared/bridge';
/** One page that asks for all of these add-on hosts at once. */
export async function requestAddonPermission(url: string, urls: string[] = []): Promise<void> {
  const origins = [...new Set([url, ...urls].map(u => publicHttps(u).origin))].slice(0, 40);
  await browser.tabs.create({ url: browser.runtime.getURL(`/grant.html?kind=addons&origins=${encodeURIComponent(origins.join(','))}`) });
}
export async function addonFetch(request: HttpRequest): Promise<HttpResponse> {
  const url = publicHttps(request.url);
  if ((request.method && request.method !== 'GET') || request.body || (request.headers && Object.keys(request.headers).length)) throw new Error('Addon requests only allow GET without custom headers');
  if (!await browser.permissions.contains({ origins: [`${url.origin}/*`] })) throw Object.assign(new Error('Allow this addon host from Audio add-ons first'), { code: 'permission' });
  const response = await fetch(url, { credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(8000) });
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Empty addon response');
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) { const item = await reader.read(); if (item.done) break; size += item.value.length; if (size > 2 * 1024 * 1024) throw new Error('Addon response too large'); chunks.push(item.value); }
  } finally { await reader.cancel().catch(() => {}); }
  const buffer = new Uint8Array(size); let at = 0; chunks.forEach(c => { buffer.set(c, at); at += c.length; });
  return { status: response.status, headers: { 'retry-after': response.headers.get('retry-after') ?? '' }, body: new TextDecoder().decode(buffer), bodyEncoding: 'text', url: url.href };
}
