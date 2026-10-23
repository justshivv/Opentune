import test from 'node:test';
import assert from 'node:assert/strict';
import { createAddon, flacInfo } from './worker.mjs';
const req = path => new Request(`https://addon.example${path}`);
const json = (value, init) => new Response(JSON.stringify(value), init);
function flac({ rate = 96000, bits = 24, channels = 2, duration = 180 } = {}) {
  const b = new Uint8Array(42); b.set(new TextEncoder().encode('fLaC')); b[4] = 128; b[7] = 34;
  const view = new DataView(b.buffer);
  view.setBigUint64(18, BigInt(rate) << 44n | BigInt(channels - 1) << 41n | BigInt(bits - 1) << 36n | BigInt(rate * duration));
  return b;
}
const raw = { trackId: 'recording-1', title: 'Wo Noor', artistNames: ['AP Dhillon'], duration: 180000,
  isrc: 'TEST12345678', playable: true, artwork: 'https://images.example/cover.jpg' };
async function firstTrack(handler, source = 'monochrome') {
  const response = await handler(req(`/${source}/search?q=Wo%20Noor`));
  assert.equal(response.status, 200); return (await response.json()).tracks[0];
}
test('both source manifests work with OpenTune; health does not claim upstream availability', async () => {
  const h = createAddon({ fetcher: () => { throw Error('No request expected'); } });
  for (const source of ['monochrome', 'archive']) {
    const m = await (await h(req(`/${source}/manifest.json`))).json();
    assert.deepEqual(m.resources, ['search', 'stream']); assert.equal(m.settings[0].default, 'best');
    assert.equal((await (await h(req(`/${source}/health`))).json()).upstreamVerified, false);
  }
  assert.equal((await h(new Request('https://addon.example', { method: 'OPTIONS' }))).status, 204);
  assert.equal((await h(new Request('https://addon.example', { method: 'POST' }))).status, 405);
  assert.equal((await h(req('/search?quality=LOW&q=song'))).status, 400);
});
test('Monochrome search maps identity, excludes unavailable tracks, resolves measured FLAC', async () => {
  const calls = [];
  const h = createAddon({ fetcher: async (url, options) => {
    calls.push({ url, options });
    if (url.includes('/search/')) return json({ tracks: [raw, { ...raw, playable: false }, { ...raw, trackId: '../bad' }] });
    return new Response(flac());
  } });
  const track = await firstTrack(h);
  assert.equal(track.artist, 'AP Dhillon'); assert.equal(track.duration, 180); assert.equal(track.isrc, raw.isrc);
  const response = await h(req(`/stream/${track.id}`)); const stream = await response.json();
  assert.equal(response.status, 200); assert.equal(stream.url, 'https://tracks.monochrome.st/track/recording-1');
  assert.equal(stream.sampleRate, 96000); assert.equal(stream.bitDepth, 24); assert.equal(stream.duration, 180);
  assert.equal(calls[1].options.headers.Range, 'bytes=0-65535');
  assert.match(calls[1].options.headers['User-Agent'], /^OpenTune-/);
  assert.equal(calls[1].options.headers.Origin, undefined);
});
test('CD FLAC stays CD quality; arbitrary or malformed content never earns a FLAC label', () => {
  assert.equal(flacInfo(flac({ rate: 44100, bits: 16 })).bitDepth, 16);
  for (const bytes of [new Uint8Array(), new TextEncoder().encode('<html>cloudflare error</html>'), flac({ rate: 0 }), flac({ duration: 0 })]) {
    assert.throws(() => flacInfo(bytes));
  }
  const b = flac(); b[4] = 1; assert.throws(() => flacInfo(b));
});
test('HTTP 200 HTML and lossy media are rejected with meaningful errors', async () => {
  const h = createAddon({ fetcher: async url => url.includes('/search/') ? json({ tracks: [raw] }) : new Response('<html>not a FLAC</html>') });
  const t = await firstTrack(h); const r = await h(req(`/stream/${t.id}`));
  assert.equal(r.status, 502); assert.match((await r.json()).error, /native FLAC/);
});
test('range response must start at byte zero', async () => {
  const h = createAddon({ fetcher: async url => url.includes('/search/') ? json({ tracks: [raw] }) : new Response(flac(), { status: 206, headers: { 'Content-Range': 'bytes 1-42/100' } }) });
  const t = await firstTrack(h); assert.equal((await h(req(`/stream/${t.id}`))).status, 502);
});
test('rate limits are preserved and suppress further requests until Retry-After', async () => {
  let calls = 0, time = 100000;
  const h = createAddon({ now: () => time, fetcher: async () => { calls++; return json({ error: 'quota' }, { status: 429, headers: { 'Retry-After': '30' } }); } });
  const first = await h(req('/search?q=one')); assert.equal(first.status, 429); assert.equal(first.headers.get('Retry-After'), '30');
  const second = await h(req('/search?q=two')); assert.equal(second.status, 429); assert.equal(calls, 1);
  time += 31000; await h(req('/search?q=two')); assert.equal(calls, 2);
});
test('refused access is reported, never retried with impersonated headers', async () => {
  let calls = 0;
  const h = createAddon({ fetcher: async () => { calls++; return new Response('', { status: 403 }); } });
  const r = await h(req('/search?q=song')); assert.equal(r.status, 502); assert.match((await r.json()).error, /refused access.*403/); assert.equal(calls, 1);
});
test('successful requests are coalesced and cached; failures are not cached', async () => {
  let calls = 0;
  const h = createAddon({ fetcher: async () => { calls++; return json({ tracks: [raw] }); } });
  await Promise.all([firstTrack(h), firstTrack(h)]); assert.equal(calls, 1);
  let failures = 0; const failed = createAddon({ fetcher: async () => { failures++; return new Response('', { status: 503 }); } });
  await failed(req('/search?q=track')); await failed(req('/search?q=track')); assert.equal(failures, 2);
});
test('malformed recording IDs and cross-source IDs cannot select arbitrary hosts or paths', async () => {
  let calls = 0; const h = createAddon({ fetcher: async () => { calls++; return json({ tracks: [raw] }); } });
  const t = await firstTrack(h);
  for (const path of ['/stream/bad', `/archive/stream/${t.id}`, '/stream/https%3A%2F%2Flocalhost', '/__proto__/manifest.json']) {
    const r = await h(req(path)); assert.ok(r.status >= 400);
  }
  assert.equal(calls, 1);
});
test('Archive preserves track-level titles and filenames, skips ambiguous files, verifies file format', async () => {
  const h = createAddon({ fetcher: async url => {
    if (url.includes('/advancedsearch.php')) return json({ response: { docs: [{ identifier: 'free-recording' }] } });
    if (url.includes('/metadata/')) return json({ metadata: { title: 'Concert', creator: 'An Artist' }, files: [
      { name: '01 My Song.flac', title: 'My Song', format: 'Flac', length: '3:00' },
      { name: '02.flac', format: 'Flac', length: '200' },
      { name: '../03.flac', title: 'Bad path', format: 'Flac', length: '200' },
    ] });
    assert.equal(url, 'https://archive.org/download/free-recording/01%20My%20Song.flac');
    return new Response(flac({ rate: 44100, bits: 16 }));
  } });
  const result = await (await h(req('/archive/search?q=My%20Song'))).json(); assert.equal(result.tracks.length, 1);
  const t = result.tracks[0]; assert.equal(t.title, 'My Song'); assert.equal(t.duration, 180);
  const r = await h(req(`/archive/stream/${t.id}`)); assert.equal(r.status, 200); assert.equal((await r.json()).bitDepth, 16);
});
test('oversized JSON is bounded, errors and network details do not leak response bodies', async () => {
  const h = createAddon({ fetcher: async () => new Response('x'.repeat(2 * 1024 * 1024 + 1)) });
  assert.match((await (await h(req('/search?q=song'))).json()).error, /size limit/);
  const failed = createAddon({ fetcher: async () => { throw Error('secret token=do-not-leak'); } });
  const r = await failed(req('/search?q=song')); assert.equal(r.status, 503); assert.doesNotMatch(await r.text(), /secret|token/);
});
test('probe cancels the response stream when the server ignores Range', async () => {
  let cancelled = false;
  const h = createAddon({ fetcher: async url => url.includes('/search/') ? json({ tracks: [raw] }) : new Response(new ReadableStream({
    start(controller) { const b = new Uint8Array(65536); b.set(flac()); controller.enqueue(b); },
    cancel() { cancelled = true; },
  })) });
  const t = await firstTrack(h); assert.equal((await h(req(`/stream/${t.id}`))).status, 200); assert.equal(cancelled, true);
});
