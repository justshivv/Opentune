import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { generateKeyPairSync } from 'node:crypto';
const base = new URL(process.env.ADDON_URL);
assert.equal(base.protocol, 'https:');
assert.match(base.hostname, /^opentune-lossless-addon\.[a-z0-9-]+\.workers\.dev$/);
async function get(path) {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(20000), headers: { Origin: 'https://justshivv.github.io' } });
  const body = await response.json();
  return { response, body };
}
const report = { checkedAt: new Date().toISOString(), base: base.origin, manifests: [], upstream: null };
const policy = await get('/auth/policy');
assert.equal(policy.response.status, 200);
assert.ok(['staged', 'strict'].includes(policy.body.mode));
const configuration = await readFile(new URL('./wrangler.toml', import.meta.url), 'utf8');
assert.equal(policy.body.mode, /^VERIFICATION_MODE\s*=\s*"([^"]+)"/m.exec(configuration)?.[1], 'Deployed verification policy must match the committed configuration');
report.security = { mode: policy.body.mode };
const rejected = await get('/auth/web/info?id=' + 'a'.repeat(43));
assert.equal(rejected.response.status, 401);
const challenge = await get('/auth/challenge');
assert.equal(challenge.response.status, 200);
assert.match(challenge.body.challenge, /^[A-Za-z0-9_-]{43}$/);
const fake = await fetch(new URL('/auth/enroll', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ challenge: challenge.body.challenge, chain: [], proof: '' }), signal: AbortSignal.timeout(20000) });
const fakeBody = await fake.json();
assert.equal(fake.status, 403, `Enrollment check: ${fakeBody.code || 'no diagnostic code'}`);
const replay = await fetch(new URL('/auth/enroll', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ challenge: challenge.body.challenge, chain: [], proof: '' }), signal: AbortSignal.timeout(20000) });
assert.equal(replay.status, 401); await replay.body?.cancel();
report.security.unauthenticatedApproval = rejected.response.status;
report.security.challengeIssued = true;
report.security.fakeEnrollment = fake.status;
report.security.challengeReplay = replay.status;
const browserKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey.export({ format: 'jwk' });
const pairingResponse = await fetch(new URL('/auth/web/start', base), { method: 'POST',
  headers: { 'Content-Type': 'application/json', Origin: 'https://justshivv.github.io' }, body: JSON.stringify({ key: browserKey }), signal: AbortSignal.timeout(20000) });
assert.equal(pairingResponse.status, 200);
const pairing = await pairingResponse.json();
assert.match(pairing.id, /^[A-Za-z0-9_-]{43}$/);
const pendingResponse = await fetch(new URL('/auth/web/status', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: pairing.id, poll: pairing.poll }), signal: AbortSignal.timeout(20000) });
assert.equal(pendingResponse.status, 200);
assert.equal((await pendingResponse.json()).session, null);
report.security.browserWaitsForApproval = true;
for (const source of ['monochrome', 'archive', 'musopen', 'netease']) {
  const { response, body } = await get(`/${source}/manifest.json`);
  assert.equal(response.status, 200); assert.equal(body.id, `org.opentune.${source}`);
  assert.deepEqual(body.resources, ['search', 'stream']); assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
  report.manifests.push({ source, status: response.status, url: `${base.origin}/${source}/manifest.json` });
}
if (policy.body.mode === 'strict') {
  report.security.unsignedAccess = [];
  for (const source of ['monochrome', 'archive', 'musopen', 'netease']) {
    for (const route of ['search?q=295&quality=best', 'stream/untrusted-id?quality=best']) {
      for (const agent of ['BitChord/v1.8.1', 'OpenTune/verified-addon-v1']) {
        const r = await fetch(new URL(`/${source}/${route}`, base), { signal: AbortSignal.timeout(20000), headers: {
          'User-Agent': agent, Origin: 'https://justshivv.github.io', Authorization: 'Bearer ' + 'a'.repeat(43),
        } });
        assert.equal(r.status, 401, `${source}/${route}: app names and copied bearer values must not authorize access`);
        await r.body?.cancel();
        report.security.unsignedAccess.push({ source, route: route.split('?')[0], agent, status: r.status });
      }
    }
  }
}
const search = await get('/monochrome/search?q=Wo%20Noor%20AP%20Dhillon&quality=best');
if (policy.body.mode === 'strict') assert.equal(search.response.status, 401);
const track = search.body.tracks?.find(t => t.title.toLowerCase() === 'wo noor' && t.artist.toLowerCase().includes('ap dhillon'));
report.upstream = { searchStatus: search.response.status, matchingRecording: Boolean(track) };
if (track) {
  const stream = await get(`/monochrome/stream/${encodeURIComponent(track.id)}?quality=best`);
  report.upstream.streamStatus = stream.response.status;
  if (stream.response.status === 200) {
    assert.equal(stream.body.codec, 'flac'); assert.ok(stream.body.bitDepth >= 8); assert.ok(stream.body.sampleRate >= 8000);
    report.upstream.flacHeader = { bitDepth: stream.body.bitDepth, sampleRate: stream.body.sampleRate, channels: stream.body.channels, duration: stream.body.duration };
  } else report.upstream.error = stream.body.error || 'Source unavailable';
} else report.upstream.error = search.body.error || 'No matching recording';
const classical = await get('/musopen/search?q=Goldberg%20Variations%20Aria%20Da%20Capo');
report.musopen = { searchStatus: classical.response.status, candidates: classical.body.tracks?.length || 0 };
if (policy.body.mode === 'strict') assert.equal(classical.response.status, 401);
const classicalTrack = classical.body.tracks?.[0];
if (classicalTrack) {
  const s = await get(`/musopen/stream/${encodeURIComponent(classicalTrack.id)}`);
  report.musopen.streamStatus = s.response.status;
  if (s.response.status === 200) {
    assert.equal(s.body.codec, 'flac');
    report.musopen.flacHeader = { bitDepth: s.body.bitDepth, sampleRate: s.body.sampleRate, channels: s.body.channels, duration: s.body.duration };
  } else report.musopen.error = s.body.error;
} else report.musopen.error = classical.body.error || 'No matching recording';
await writeFile('lossless-deployment-report.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (policy.body.mode !== 'strict' && !report.upstream.flacHeader) console.log('::warning::Worker manifests are live, but upstream playback is currently unavailable; see the deployment report.');
