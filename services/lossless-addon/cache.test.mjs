import test from 'node:test';
import assert from 'node:assert/strict';
import { withMetadataCache } from './cache.mjs';
import { withAppVerification } from './auth.mjs';
function memoryCache() {
  const entries = new Map();
  return { match: async key => entries.get(key.url)?.clone(), put: async (key, response) => entries.set(key.url, response.clone()) };
}
const request = () => new Request('https://addon.example/monochrome/search?q=test');
test('shared metadata survives handler instances but never caches authorization headers', async () => {
  const store = memoryCache(); let calls = 0;
  const handler = async () => { calls++; return Response.json({ tracks: [{ id: 'one' }] }); };
  const a = withMetadataCache(handler, { cache: () => store });
  const b = withMetadataCache(handler, { cache: () => store });
  const [one, two] = await Promise.all([a(request()), a(request())]);
  assert.deepEqual(await one.json(), await two.json());
  const response = await b(request());
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(calls, 1);
});
test('cache failure leaves the source usable and error responses are retried', async () => {
  let calls = 0;
  const handler = withMetadataCache(async () => { calls++; return Response.json({ error: 'Unavailable' }, { status: 503 }); }, { cache: () => memoryCache() });
  assert.equal((await handler(request())).status, 503);
  assert.equal((await handler(request())).status, 503);
  assert.equal(calls, 2);
  const broken = withMetadataCache(async () => Response.json({ tracks: [] }), { cache: () => ({ match: async () => { throw Error('cache down'); }, put: async () => { throw Error('cache down'); } }) });
  assert.equal((await broken(request())).status, 200);
});
test('a warm cache cannot bypass strict device verification', async () => {
  let calls = 0;
  const store = memoryCache();
  const handler = withAppVerification(withMetadataCache(async () => { calls++; return Response.json({ tracks: [] }); }, { cache: () => store }));
  assert.equal((await handler(request(), { AUTH: {}, VERIFICATION_MODE: 'staged' })).status, 200);
  assert.equal((await handler(request(), { AUTH: {}, VERIFICATION_MODE: 'strict' })).status, 401);
  assert.equal(calls, 1);
});
