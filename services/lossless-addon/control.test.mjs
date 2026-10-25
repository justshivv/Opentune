import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createPublicKey, verify } from 'node:crypto';
import { ConfigStore, createControl, defaults, validateConfig } from './control.mjs';

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'opentune-control-'));
  const store = new ConfigStore(dir);
  return { dir, store, close() { store.close(); rmSync(dir, { recursive: true, force: true }); } };
}
test('only supported, bounded settings can be published', () => {
  assert.deepEqual(validateConfig(defaults()), defaults());
  for (const change of [{ network: { timeoutMs: 1, retries: 10 } }, { verification: false }, { sections: [] },
    { banners: [{ id: 'test', title: 'T', body: '', action: 'Open', url: 'javascript:alert(1)', platforms: ['android'], enabled: true }] },
    { sources: [{ id: 'private', name: 'private', url: 'https://127.0.0.1/', enabled: true }] }]) {
    assert.throws(() => validateConfig({ ...defaults(), ...change }));
  }
});
test('publish, conflict, rollback, signature and restart retain a monotonic history', () => {
  const f = fixture();
  try {
    assert.equal(f.store.envelope(), null);
    f.store.publish({ ...defaults(), communityPrompts: false }, 0);
    assert.throws(() => f.store.publish(defaults(), 0), /Configuration changed/);
    f.store.publish(defaults(), 1);
    const restored = f.store.rollback(1, 2);
    assert.equal(restored.revision, 3); assert.equal(restored.data.communityPrompts, false);
    const envelope = f.store.envelope();
    const key = createPublicKey({ key: Buffer.from(f.store.publicKey().publicKey, 'base64'), type: 'spki', format: 'der' });
    assert(verify('sha256', Buffer.from(envelope.payload), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(envelope.signature, 'base64url')));
    assert(!verify('sha256', Buffer.from(envelope.payload + 'x'), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(envelope.signature, 'base64url')));
    const second = new ConfigStore(f.dir);
    try { assert.deepEqual(second.publicKey(), f.store.publicKey()); assert.equal(second.current().revision, 3); } finally { second.close(); }
  } finally { f.close(); }
});
test('owner login, origin checks and edit conflicts protect publication; public data has no secrets', async () => {
  const f = fixture(), env = { CONTROL_ADMIN_USERNAME: 'owner', CONTROL_ADMIN_PASSWORD: 'a sufficiently long password' };
  const handle = createControl(f.store, env);
  const call = (path, body, token, origin) => handle(new Request('https://addon.example' + path, {
    method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(origin ? { Origin: origin } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }));
  try {
    assert.equal((await call('/control/config')).status, 401);
    assert.equal((await call('/control/login', { username: 'owner', password: 'wrong' })).status, 401);
    const { token } = await (await call('/control/login', { username: 'owner', password: env.CONTROL_ADMIN_PASSWORD })).json();
    assert.equal((await call('/control/config', { expectedRevision: 0, data: defaults() }, token, 'https://evil.example')).status, 403);
    assert.equal((await call('/control/config', { expectedRevision: 0, data: defaults() }, token)).status, 200);
    assert.equal((await call('/control/config', { expectedRevision: 0, data: defaults() }, token)).status, 409);
    const published = await call('/v1/app-config');
    assert.equal(published.status, 200);
    const envelope = await published.json();
    const body = JSON.parse(Buffer.from(envelope.payload, 'base64url'));
    assert.equal(body.schemaVersion, 1); assert.equal(body.revision, 1);
    assert(!JSON.stringify(body).includes(env.CONTROL_ADMIN_PASSWORD));
    env.CONTROL_ADMIN_PASSWORD = 'a different sufficiently long password';
    assert.equal((await call('/control/config', undefined, token)).status, 401);
    assert.equal(await call('/monochrome/search'), null);
  } finally { f.close(); }
});
