/** Read-only research. Reports measured direct FLAC separately from provider claims. */
import { flacInfo, createAddon } from './worker.mjs';
import { writeFile } from 'node:fs/promises';
const UA = 'OpenTune-Source-Research/1.0';
async function fetchBounded(url, media = false) {
  const u = new URL(url);
  if (u.protocol !== 'https:') throw Error('HTTPS required');
  const r = await fetch(u, { signal: AbortSignal.timeout(8000), headers: { 'User-Agent': UA, Accept: media ? '*/*' : 'application/json', ...(media ? { Range: 'bytes=0-65535' } : {}) } });
  if (!r.ok) { await r.body?.cancel(); throw Error(`HTTP ${r.status}`); }
  const reader = r.body.getReader(), limit = media ? 65536 : 1048576;
  const pieces = []; let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read(); if (done) break;
      if (!media && length + value.length > limit) throw Error('Response exceeds limit');
      const piece = value.subarray(0, limit - length); pieces.push(piece); length += piece.length;
      if (length >= limit) break;
    }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const p of pieces) { bytes.set(p, offset); offset += p.length; }
  return media ? bytes : JSON.parse(new TextDecoder().decode(bytes));
}
const report = { checkedAt: new Date().toISOString(), scope: 'Independent public source checks; native FLAC headers only, no full-song downloads or device playback.', results: [] };
for (const [name, base] of [
  ['Lossless Bridge', 'https://losslessbridge.de.deplexo.com'],
  ['BluBit Station', 'https://bluebit.jucepe0929.workers.dev'],
  ['Juanjob hi-res', 'https://bitchord-hires-addon.vercel.app'],
]) {
  const result = { name, query: 'Blinding Lights The Weeknd' };
  try {
    const m = await fetchBounded(`${base}/manifest.json`);
    result.manifest = { name: m.name, version: m.version };
    const params = new URLSearchParams();
    for (const s of m.settings || []) if (['string', 'number', 'boolean'].includes(typeof s.default)) params.set(s.key, String(s.default));
    const options = m.settings?.find(s => s.key === 'quality')?.options?.map(o => String(o.value)) || [];
    const quality = options.find(o => /hi.?res|max|best/i.test(o)) || options.find(o => /lossless|flac/i.test(o)) || 'LOSSLESS';
    params.set('quality', quality); params.set('q', result.query);
    const search = await fetchBounded(`${base}/search?${params}`);
    const t = search.tracks?.find(t => t.title?.toLowerCase() === 'blinding lights' && String(t.artist).toLowerCase().includes('weeknd'));
    result.candidates = search.tracks?.length || 0;
    if (!t) throw Error('No matching recording');
    params.delete('q'); const stream = await fetchBounded(`${base}/stream/${encodeURIComponent(t.id)}?${params}`);
    result.requestedQuality = quality;
    result.declared = { codec: stream.codec, manifest: stream.manifest, bitDepth: stream.bitDepth, sampleRate: stream.sampleRate };
    if (stream.encrypted && stream.encrypted !== 'false') throw Error('Encrypted or unsupported response');
    if (!stream.url) throw Error('No media URL');
    const bytes = await fetchBounded(stream.url, true);
    try { result.measured = flacInfo(bytes); }
    catch { result.note = 'Not native FLAC in the bounded response; segmented/container format remains unverified by this probe.'; }
  } catch (e) { result.error = /^HTTP \d+$|^No matching recording$|^No media URL$/.test(e.message) ? e.message : 'Request or format verification failed'; }
  report.results.push(result);
}
const mono = createAddon();
const search = await mono(new Request('https://addon.example/search?q=Blinding%20Lights%20The%20Weeknd'));
const data = await search.json();
const t = data.tracks?.find(t => t.title.toLowerCase() === 'blinding lights' && t.artist.toLowerCase().includes('weeknd'));
const result = { name: 'Monochrome', query: 'Blinding Lights The Weeknd', searchStatus: search.status };
if (t) {
  const r = await mono(new Request(`https://addon.example/stream/${t.id}`)); const s = await r.json(); result.streamStatus = r.status;
  if (r.ok) result.measured = { bitDepth: s.bitDepth, sampleRate: s.sampleRate, channels: s.channels, duration: s.duration };
  else result.error = s.error;
} else result.error = data.error || 'No matching recording';
report.results.push(result);
await writeFile('lossless-candidate-report.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
