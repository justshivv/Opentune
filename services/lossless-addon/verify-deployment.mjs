import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
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
report.security = { mode: policy.body.mode };
const rejected = await get('/auth/web/info?id=' + 'a'.repeat(43));
assert.equal(rejected.response.status, 401);
const challenge = await get('/auth/challenge');
assert.equal(challenge.response.status, 200);
assert.match(challenge.body.challenge, /^[A-Za-z0-9_-]{43}$/);
const fake = await fetch(new URL('/auth/enroll', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ challenge: challenge.body.challenge, chain: [], proof: '' }), signal: AbortSignal.timeout(20000) });
assert.equal(fake.status, 403); await fake.body?.cancel();
const replay = await fetch(new URL('/auth/enroll', base), { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ challenge: challenge.body.challenge, chain: [], proof: '' }), signal: AbortSignal.timeout(20000) });
assert.equal(replay.status, 401); await replay.body?.cancel();
report.security.unauthenticatedApproval = rejected.response.status;
report.security.challengeIssued = true;
report.security.fakeEnrollment = fake.status;
report.security.challengeReplay = replay.status;
for (const source of ['monochrome', 'archive']) {
  const { response, body } = await get(`/${source}/manifest.json`);
  assert.equal(response.status, 200); assert.equal(body.id, `org.opentune.${source}`);
  assert.deepEqual(body.resources, ['search', 'stream']); assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
  report.manifests.push({ source, status: response.status, url: `${base.origin}/${source}/manifest.json` });
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
await writeFile('lossless-deployment-report.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (!report.upstream.flacHeader) console.log('::warning::Worker manifests are live, but upstream playback is currently unavailable; see the deployment report.');
