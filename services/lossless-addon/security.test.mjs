import test from 'node:test';
import assert from 'node:assert/strict';
import { withAbuseProtection } from './security.mjs';
const request = (headers = {}) => new Request('https://addon.example/search?q=song', { headers: { 'CF-Connecting-IP': '198.51.100.1', ...headers } });
test('limit is checked before upstream access and returns Retry-After', async () => {
  let accessed = false;
  const h = withAbuseProtection(async () => { accessed = true; return new Response('{}'); });
  const r = await h(request(), { REQUEST_LIMITER: { limit: async () => ({ success: false }) } });
  assert.equal(r.status, 429); assert.equal(r.headers.get('Retry-After'), '60'); assert.equal(accessed, false);
});
test('different queries and spoofed client identity headers share the same address budget', async () => {
  const keys = [];
  const h = withAbuseProtection(async () => new Response('{}'));
  const env = { REQUEST_LIMITER: { limit: async ({ key }) => { keys.push(key); return { success: true }; } } };
  await h(request({ 'User-Agent': 'OpenTune/99', 'X-Forwarded-For': '1.2.3.4', 'X-Client-ID': 'a' }), env);
  await h(new Request('https://addon.example/stream/other', { headers: { 'CF-Connecting-IP': '198.51.100.1', 'X-Client-ID': 'b' } }), env);
  assert.equal(keys[0], keys[1]); assert.doesNotMatch(keys[0], /198\.51/);
  await h(request({ 'CF-Connecting-IP': '198.51.100.2' }), env); assert.notEqual(keys[0], keys[2]);
});
test('limiter outage or missing binding cannot silently disable protection', async () => {
  const h = withAbuseProtection(() => { throw Error('Upstream must not run'); });
  assert.equal((await h(request())).status, 503);
  assert.equal((await h(request(), { REQUEST_LIMITER: { limit: async () => { throw Error('outage'); } } })).status, 503);
});
test('missing edge address is rejected; CORS preflight remains available without upstream access', async () => {
  const h = withAbuseProtection(req => new Response(null, { status: req.method === 'OPTIONS' ? 204 : 200 }));
  const env = { REQUEST_LIMITER: { limit: async () => ({ success: true }) } };
  assert.equal((await h(new Request('https://addon.example/search'), env)).status, 400);
  assert.equal((await h(new Request('https://addon.example/manifest.json', { method: 'OPTIONS' }))).status, 204);
});
test('normal requests preserve responses and add nosniff', async () => {
  const h = withAbuseProtection(() => new Response('{"tracks":[]}', { headers: { 'Access-Control-Allow-Origin': '*' } }));
  const r = await h(request(), { REQUEST_LIMITER: { limit: async () => ({ success: true }) } });
  assert.equal(r.status, 200); assert.equal(r.headers.get('X-Content-Type-Options'), 'nosniff');
  assert.deepEqual(await r.json(), { tracks: [] });
});
test('unsupported methods and oversized URLs do not call a source', async () => {
  const h = withAbuseProtection(() => { throw Error('Must not run'); });
  assert.equal((await h(new Request('https://addon.example', { method: 'POST' }))).status, 405);
  assert.equal((await h(new Request('https://addon.example/search?q=' + 'x'.repeat(4096)))).status, 414);
});
