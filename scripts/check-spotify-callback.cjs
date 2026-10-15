const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('site/spotify/callback/callback.js', 'utf8');
function callback(search) {
  const nodes = {message: {}, open: {hidden: true}};
  let cleared = false;
  vm.runInNewContext(script, {
    URLSearchParams, location: {search, pathname: '/Opentune/spotify/callback/'},
    history: {replaceState() { cleared = true; }},
    document: {getElementById(id) { return nodes[id]; }}
  });
  assert.ok(cleared);
  return nodes;
}
test('hands off a code only to OpenTune and clears browser history', () => {
  const nodes = callback('?state=' + 'a'.repeat(43) + '&code=one%26two');
  assert.equal(nodes.open.hidden, false);
  const uri = new URL(nodes.open.href);
  assert.equal(uri.protocol, 'opentune:');
  assert.equal(uri.hostname, 'spotify-callback');
  assert.equal(uri.searchParams.get('code'), 'one&two');
});
test('rejects duplicate states, duplicate codes, missing fields and mixed error/code', () => {
  const state = 'state=' + 'a'.repeat(43);
  for (const query of ['', '?code=x', '?' + state + '&code=x&code=y', '?' + state + '&' + state + '&code=x', '?' + state + '&code=x&error=access_denied']) {
    assert.equal(callback(query).open.hidden, true);
  }
});
