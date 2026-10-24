import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';

const sha = v => createHash('sha256').update(v).digest('hex');

async function start(extra = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'opentune-addon-'));
  const port = 20000 + Math.floor(Math.random() * 20000);
  const child = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', new URL('./server.mjs', import.meta.url).pathname], {
    env: { ...process.env, PORT: String(port), DATA_DIR: dir, PUBLIC_HOST: 'addon.example', APP_CERT_SHA256: 'a'.repeat(64), ...extra },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise((resolve, reject) => {
    child.stdout.on('data', d => { if (String(d).includes('OpenTune add-on on')) resolve(); });
    child.on('exit', code => reject(new Error('server exited ' + code)));
  });
  return { base: `http://127.0.0.1:${port}`, dir, stop: () => { child.kill(); rmSync(dir, { recursive: true, force: true }); } };
}

test('the VPS server answers like the Worker: public manifests, signed searches, no replays', async () => {
  const server = await start();
  try {
    assert.equal((await fetch(server.base + '/monochrome/manifest.json')).status, 200);
    assert.equal((await fetch(server.base + '/auth/challenge')).status, 200);
    assert.equal((await fetch(server.base + '/archive/search?q=x&quality=best')).status, 401);
    // A device session, as enrollment would leave it.
    const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const session = randomBytes(32).toString('base64url');
    const db = new DatabaseSync(join(server.dir, 'auth.sqlite'));
    db.prepare('INSERT INTO state(name, data, alarm) VALUES(?, ?, ?)').run('session:' + sha(session),
      JSON.stringify({ item: { key: keys.publicKey.export({ type: 'spki', format: 'pem' }), kind: 'native', expires: Date.now() + 3600_000 } }),
      Date.now() + 3600_000);
    db.close();
    // Empty query: answered without asking the source, so no network is needed.
    const path = '/archive/search?quality=best';
    const time = String(Date.now()), nonce = randomBytes(24).toString('base64url');
    const signature = sign('sha256', Buffer.from(['OpenTune-Addon-v1', session, time, nonce, 'GET', path, sha('')].join('\n')), keys.privateKey).toString('base64url');
    const headers = { Authorization: 'Bearer ' + session, 'X-OpenTune-Time': time, 'X-OpenTune-Nonce': nonce, 'X-OpenTune-Signature': signature };
    const first = await fetch(server.base + path, { headers });
    assert.equal(first.status, 200);
    assert.deepEqual(await first.json(), { tracks: [] });
    assert.equal((await fetch(server.base + path, { headers })).status, 401);
  } finally { server.stop(); }
});

test('the VPS server can be paused from its settings', async () => {
  const server = await start({ PAUSED: 'true' });
  try {
    const r = await fetch(server.base + '/netease/search?q=x');
    assert.equal(r.status, 503);
    assert.equal(r.headers.get('x-opentune-paused'), '1');
    assert.equal((await fetch(server.base + '/netease/manifest.json')).status, 200);
  } finally { server.stop(); }
});

test('callers cannot pick the address they are limited by', async () => {
  const server = await start({ REQUESTS_PER_MINUTE: '3' });
  try {
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await fetch(server.base + '/auth/policy', { headers: { 'CF-Connecting-IP': '203.0.113.' + i } })).status);
    assert.deepEqual(statuses, [200, 200, 200, 429, 429]);
  } finally { server.stop(); }
});
