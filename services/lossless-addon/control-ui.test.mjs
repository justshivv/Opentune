import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('./control.js', import.meta.url), 'utf8');
function dashboard(fetch = async () => { throw new Error('Unexpected request'); }, storage = new Map(), storageBlocked = false) {
  const fields = new Map();
  const blank = () => ({ value: '', checked: false, focus() { this.focused = true; }, append() {}, replaceChildren() {}, setAttribute() {} });
  const element = id => {
    if (!fields.has(id)) fields.set(id, { ...blank(), hidden: id === 'editor' });
    return fields.get(id);
  };
  const context = vm.createContext({ document: { getElementById: element, createElement: blank }, fetch,
    sessionStorage: {
      getItem(key) { if (storageBlocked) throw new Error('Storage disabled'); return storage.get(key) || null; },
      setItem(key, value) { if (storageBlocked) throw new Error('Storage disabled'); storage.set(key, String(value)); },
      removeItem(key) { if (storageBlocked) throw new Error('Storage disabled'); storage.delete(key); },
    },
    FormData: class { constructor(form) { this.values = form.values; } *[Symbol.iterator]() { yield* Object.entries(this.values); } },
  });
  vm.runInContext(source, context);
  vm.runInContext('data = { sources: null, links: { discord: "https://discord.gg/example" } }', context);
  element('timeout').value = '8000'; element('retries').value = '2';
  return { context, element, storage, run: code => vm.runInContext(code, context) };
}

test('blank optional lists produce a publishable empty-list draft', () => {
  const ui = dashboard();
  ui.element('banners').value = ' \n ';
  const draft = JSON.parse(JSON.stringify(ui.run('draft()')));
  assert.deepEqual(draft.banners, []);
  assert.deepEqual(draft.playlists, []);
  assert.equal(draft.sources, null);
  assert.deepEqual(draft.network, { timeoutMs: 8000, retries: 2 });
});

test('malformed lists identify and focus the field before a publish request', async () => {
  for (const [key, label] of [['banners', 'Announcements'], ['playlists', 'Featured playlists']]) {
    let requests = 0;
    const ui = dashboard(async () => { requests++; });
    ui.element(key).value = '[';
    await ui.run("action(async () => { await api('config', { data: draft() }); })");
    assert.match(ui.element('status').textContent, new RegExp(label + ': invalid JSON'));
    assert.equal(ui.element(key).focused, true);
    assert.equal(ui.element('publish').disabled, false);
    assert.equal(requests, 0);
    assert.equal(ui.element(key).value, '['); // Keep the owner's draft for correction.
  }
});

test('non-array JSON is rejected instead of causing a preview crash', () => {
  const ui = dashboard();
  for (const value of ['null', '{}', 'true', '"hello"']) {
    ui.element('banners').value = value;
    assert.throws(() => ui.run('draft()'), /Announcements must be a JSON array/);
  }
});

test('adding examples works after clearing either optional field', async () => {
  const ui = dashboard();
  ui.element('exampleBanner').onclick();
  ui.element('examplePlaylist').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(JSON.parse(ui.element('banners').value)[0].enabled, true);
  assert.equal(JSON.parse(ui.element('playlists').value).length, 1);
});

test('empty and non-JSON API responses name the request and HTTP status', async () => {
  for (const [body, status, kind] of [['', 200, 'empty'], ['', 502, 'empty'], ['<html>Unavailable</html>', 503, 'invalid']]) {
    const ui = dashboard(async () => new Response(body, { status }));
    await assert.rejects(ui.run("api('config')"), new RegExp(`Dashboard config request returned an ${kind} response \\(HTTP ${status}\\)`));
  }
});

test('API errors retain the server message and valid responses are accepted', async () => {
  const ui = dashboard(async () => Response.json({ error: 'Incorrect username or password' }, { status: 401 }));
  await assert.rejects(ui.run("api('login', {})"), /Incorrect username or password/);
  const valid = dashboard(async () => Response.json({ revision: 2 }));
  assert.equal((await valid.run("api('config')")).revision, 2);
});

const sessionKey = 'opentune.control.session';
const current = { revision: 1, history: [], data: { sections: [], sources: null, links: {}, banners: [], playlists: [], communityPrompts: true, network: { timeoutMs: 8000, retries: 2 } } };
const flush = () => new Promise(resolve => setImmediate(resolve));

test('login survives reload in the same tab, and sign out prevents restoration', async () => {
  const storage = new Map();
  const requests = [];
  const fetch = async (url, options) => {
    requests.push({ url, options });
    return Response.json(url.endsWith('/login') ? { token: 'server-issued-session' } : current);
  };
  const ui = dashboard(fetch, storage);
  ui.element('login').onsubmit({ preventDefault() {}, target: { values: { username: 'owner', password: 'private-password-for-test' }, reset() {} } });
  await flush();
  assert.equal(ui.element('editor').hidden, false);
  assert.deepEqual([...storage], [[sessionKey, 'server-issued-session']]);
  requests.length = 0;
  const refreshed = dashboard(fetch, storage);
  await flush();
  assert.equal(refreshed.element('editor').hidden, false);
  assert.equal(refreshed.element('login').hidden, true);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/control/config');
  assert.equal(requests[0].options.headers.Authorization, 'Bearer server-issued-session');
  refreshed.element('logout').onclick();
  assert.equal(storage.size, 0);
  requests.length = 0;
  const signedOut = dashboard(fetch, storage);
  await flush();
  assert.equal(signedOut.element('login').hidden, false);
  assert.equal(signedOut.element('editor').hidden, true);
  assert.equal(requests.length, 0);
});

test('expired or revoked session is cleared when the server rejects restoration', async () => {
  const storage = new Map([[sessionKey, 'expired-or-revoked']]);
  const ui = dashboard(async () => Response.json({ error: 'Sign in required' }, { status: 401 }), storage);
  await flush();
  assert.equal(storage.size, 0);
  assert.equal(ui.element('login').hidden, false);
  assert.equal(ui.element('editor').hidden, true);
  assert.match(ui.element('status').textContent, /Session expired or no longer valid/);
});

test('a temporary server failure does not discard the session', async () => {
  const storage = new Map([[sessionKey, 'existing-session']]);
  const ui = dashboard(async () => new Response('', { status: 502 }), storage);
  await flush();
  assert.equal(storage.get(sessionKey), 'existing-session');
  assert.equal(ui.element('login').hidden, false);
  assert.match(ui.element('status').textContent, /empty response.*HTTP 502/);
});

test('storage-disabled browsers can still sign in and sign out', async () => {
  const ui = dashboard(async url => Response.json(url.endsWith('/login') ? { token: 'temporary-session' } : current), new Map(), true);
  ui.element('login').onsubmit({ preventDefault() {}, target: { values: { username: 'owner', password: 'private-password-for-test' }, reset() {} } });
  await flush();
  assert.equal(ui.element('editor').hidden, false);
  assert.equal(ui.storage.size, 0);
  ui.element('logout').onclick();
  assert.equal(ui.element('login').hidden, false);
});
