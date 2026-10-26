/** Shared successful metadata only. Install INSIDE verification, never in front of it. */
export function withMetadataCache(handler, { cache = () => globalThis.caches?.default } = {}) {
  const pending = new Map();
  return async request => {
    const url = new URL(request.url);
    const ttl = /\/search$/.test(url.pathname) ? 60 : /\/stream\/[^/]+$/.test(url.pathname) ? 20 : 0;
    if (request.method !== 'GET' || !ttl || url.toString().length > 4096) return handler(request);
    // Credentials/proofs are not part of the cache and are verified on every incoming request.
    url.searchParams.sort();
    const key = new Request('https://opentune-cache.invalid/v1/' + encodeURIComponent(url.origin + url.pathname + url.search));
    const store = cache();
    const outward = response => {
      const headers = new Headers(response.headers);
      headers.set('Cache-Control', 'no-store');
      return new Response(response.body, { status: response.status, headers });
    };
    try {
      const hit = await store?.match(key);
      if (hit) return outward(hit);
    } catch { /* Cache availability must never become an audio outage. */ }
    if (!pending.has(key.url)) {
      if (pending.size >= 64) return new Response(JSON.stringify({ error: 'Busy; retry shortly' }), {
        status: 503, headers: { 'Content-Type': 'application/json', 'Retry-After': '5', 'Cache-Control': 'no-store' },
      });
      const task = (async () => {
        const result = await handler(request);
        if (store && result.status === 200 && result.headers.get('Content-Type')?.includes('application/json')) {
          const copy = result.clone(), headers = new Headers(copy.headers);
          headers.set('Cache-Control', `public, max-age=${ttl}`);
          try { await store.put(key, new Response(copy.body, { status: 200, headers })); } catch { /* best effort */ }
        }
        return result;
      })().finally(() => pending.delete(key.url));
      pending.set(key.url, task);
    }
    return outward((await pending.get(key.url)).clone());
  };
}
