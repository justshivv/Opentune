import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign, randomBytes } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { AddonAuthState, canonical, withAppVerification } from './auth.mjs';
import { sha256 } from './attestation.mjs';

const origin = 'https://addon.test';
const token = () => randomBytes(32).toString('base64url');
function namespace() {
  const instances = new Map();
  return { idFromName: n => n, get(name) {
    if (!instances.has(name)) {
      const data = new Map(); let queue = Promise.resolve();
      const ctx = { storage: { get: async k => structuredClone(data.get(k)), put: async (k, v) => data.set(k, structuredClone(v)), deleteAll: async () => data.clear(), setAlarm: async () => {} },
        blockConcurrencyWhile: fn => { const next = queue.then(fn); queue = next.catch(() => {}); return next; } };
      const state = new AddonAuthState(ctx); instances.set(name, { fetch: (u, init) => state.fetch(new Request(u, init)) });
    }
    return instances.get(name);
  } };
}
async function session(env, kind = 'native') {
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }), value = token();
  await env.AUTH.get('session:' + sha256(value)).fetch(origin + '/create', { method: 'POST', body: JSON.stringify({ key: keys.publicKey.export({ type: 'spki', format: 'pem' }), kind, expires: Date.now() + 3600_000 }) });
  return { value, keys, kind };
}
function signed(s, path, method = 'GET', body = '', nonce = token(), time = String(Date.now())) {
  const proof = sign('sha256', Buffer.from(canonical(s.value, time, nonce, method, path, body)), { key: s.keys.privateKey, dsaEncoding: s.kind === 'web' ? 'ieee-p1363' : 'der' }).toString('base64url');
  return new Request(origin + path, { method, ...(body ? { body } : {}), headers: { Authorization: 'Bearer ' + s.value, 'X-OpenTune-Time': time, 'X-OpenTune-Nonce': nonce, 'X-OpenTune-Signature': proof } });
}
function setup(options) {
  const env = { AUTH: namespace(), APP_CERT_SHA256: 'a'.repeat(64) }; let calls = 0;
  const app = withAppVerification(() => { calls++; return new Response('upstream'); }, options);
  return { env, app, count: () => calls };
}
test('strict default rejects fake User-Agent, Origin and copied bearer before upstream', async () => {
  const { app, env, count } = setup();
  for (const headers of [{}, { 'User-Agent': 'OpenTune', Origin: 'https://justshivv.github.io' }, { Authorization: 'Bearer ' + token() }]) {
    assert.equal((await app(new Request(origin + '/monochrome/search?q=x', { headers }), env)).status, 401);
  }
  assert.equal(count(), 0);
  assert.equal((await app(new Request(origin + '/monochrome/manifest.json'), env)).status, 200);
});
test('valid native proof accepted once; parallel replay rejected atomically', async () => {
  const { app, env, count } = setup(), s = await session(env), r = signed(s, '/monochrome/search?q=x');
  const results = await Promise.all([app(r.clone(), env), app(r.clone(), env)]);
  assert.deepEqual(results.map(x => x.status).sort(), [200, 401]); assert.equal(count(), 1);
});
test('proof binds method, path, query, body and time; bearer alone cannot authenticate', async () => {
  const { app, env, count } = setup(), s = await session(env), request = signed(s, '/monochrome/search?q=x');
  assert.equal((await app(new Request(origin + '/monochrome/search?q=y', { headers: request.headers }), env)).status, 401);
  assert.equal((await app(new Request(origin + '/auth/web/approve', { method: 'POST', headers: request.headers, body: '{}' }), env)).status, 401);
  assert.equal((await app(signed(s, '/monochrome/search', 'GET', '', token(), String(Date.now() - 120000)), env)).status, 401);
  const other = await session(env); const forged = signed(other, '/monochrome/search'); forged.headers.set('Authorization', 'Bearer ' + s.value);
  assert.equal((await app(forged, env)).status, 401); assert.equal(count(), 0);
});
test('browser is key-bound and requires explicit verified-native approval; web cannot approve', async () => {
  const { app, env } = setup(), native = await session(env), web = await session(env, 'web');
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const start = new Request(origin + '/auth/web/start', { method: 'POST', headers: { Origin: 'https://justshivv.github.io' }, body: JSON.stringify({ key: keys.publicKey.export({ format: 'jwk' }) }) });
  const pair = await (await app(start, env)).json(); assert.match(pair.id, /^[\w-]{43}$/);
  const poll = value => app(new Request(origin + '/auth/web/status', { method: 'POST', body: JSON.stringify({ id: pair.id, poll: value }) }), env);
  assert.equal((await poll(token())).status, 401);
  assert.equal((await (await poll(pair.poll)).json()).session, null);
  assert.equal((await app(signed(web, '/auth/web/approve', 'POST', JSON.stringify({ id: pair.id })), env)).status, 401);
  const info = await (await app(signed(native, '/auth/web/info?id=' + pair.id), env)).json(); assert.equal(info.code, pair.code);
  assert.equal((await app(signed(native, '/auth/web/approve', 'POST', JSON.stringify({ id: pair.id })), env)).status, 200);
  const ready = await (await poll(pair.poll)).json(); assert.ok(ready.session); assert.ok(ready.expires <= Date.now() + 12 * 3600000);
  const browser = { keys, value: ready.session, kind: 'web' };
  assert.equal((await app(signed(browser, '/monochrome/search?q=test'), env)).status, 200);
  assert.equal((await app(signed(browser, '/auth/web/info?id=' + pair.id), env)).status, 401);
  assert.equal((await app(signed(native, '/auth/web/approve', 'POST', JSON.stringify({ id: pair.id })), env)).status, 401);
});
test('browser enrollment rejects unapproved origins and private/RSA keys', async () => {
  const { app, env } = setup();
  for (const headers of [{}, { Origin: 'https://evil.test' }, { Origin: 'https://justshivv.github.io' }]) {
    const r = await app(new Request(origin + '/auth/web/start', { method: 'POST', headers, body: JSON.stringify({ key: { kty: 'RSA' } }) }), env);
    assert.ok([401, 403].includes(r.status));
  }
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const r = await app(new Request(origin + '/auth/web/start', { method: 'POST', headers: { Origin: 'https://justshivv.github.io' }, body: JSON.stringify({ key: keys.privateKey.export({ format: 'jwk' }) }) }), env);
  assert.equal(r.status, 401);
});
test('challenge one-use, verifier failure denied; missing binding fails closed', async () => {
  let verified = 0; const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const { app, env } = setup({ trust: async () => ({}), attest: () => { verified++; return keys.publicKey.export({ type: 'spki', format: 'pem' }); } });
  const { challenge } = await (await app(new Request(origin + '/auth/challenge'), env)).json();
  const enroll = () => app(new Request(origin + '/auth/enroll', { method: 'POST', body: JSON.stringify({ challenge }) }), env);
  assert.equal((await enroll()).status, 200); assert.equal((await enroll()).status, 401); assert.equal(verified, 1);
  assert.equal((await app(new Request(origin + '/monochrome/search'), {})).status, 503);
});
test('CORS allows signed browser headers; staged window is explicit only', async () => {
  const { app, env } = setup();
  const preflight = await app(new Request(origin + '/monochrome/search', { method: 'OPTIONS' }), env);
  assert.match(preflight.headers.get('Access-Control-Allow-Headers'), /X-OpenTune-Signature/);
  assert.equal((await app(new Request(origin + '/monochrome/search'), { ...env, VERIFICATION_MODE: 'staged' })).status, 200);
});
