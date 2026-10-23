/** Bounded, anonymous discovery. Never copies cookies, claims another app's identity,
 * submits a download job, ignores TLS errors, or logs signed media URLs. */
import { writeFile } from 'node:fs/promises';
import { flacInfo } from './worker.mjs';
const report = { checkedAt: new Date().toISOString(), scope: 'Public catalogue/API and native FLAC header checks only', results: [] };
async function read(url, media = false) {
  const r = await fetch(url, { headers: { 'User-Agent': 'OpenTune-Source-Research/1.0 (+https://github.com/justshivv/Opentune)', ...(media ? { Range: 'bytes=0-65535' } : {}) }, signal: AbortSignal.timeout(10000) });
  if (!r.ok) { await r.body?.cancel(); throw Error(`HTTP ${r.status}`); }
  const reader = r.body.getReader(); const chunks = []; let size = 0, cap = media ? 65536 : 2097152;
  try { for (;;) { const p = await reader.read(); if (p.done) break; const b = p.value.subarray(0, cap - size); chunks.push(b); size += b.length; if (size >= cap) break; } }
  finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size); let off = 0; for (const c of chunks) { bytes.set(c, off); off += c.length; }
  if (media) return { status: r.status, measured: flacInfo(bytes) };
  const text = new TextDecoder().decode(bytes);
  try { return { status: r.status, json: JSON.parse(text) }; } catch { return { status: r.status, html: true, challenge: /cf-chl|just a moment|captcha|verify.*human/i.test(text) }; }
}
async function probe(name, run) {
  const result = { name };
  try { Object.assign(result, await run()); }
  catch (e) { result.error = /^HTTP \d+$/.test(e.message) ? e.message : 'Network, timeout or unsupported media response'; }
  report.results.push(result);
}
for (const base of ['https://dabmusic.xyz', 'https://dab.yeet.su']) await probe(base, async () => {
  const r = await read(base + '/api/stream?trackId=356644700&quality=27');
  const j = r.json, url = j?.url || j?.data?.url;
  return { status: r.status, html: r.html, challenge: r.challenge, jsonKeys: j ? Object.keys(j) : [], ...(typeof url === 'string' && url.startsWith('https://') ? await read(url, true) : {}) };
});
await probe('Squid Qobuz', async () => {
  const r = await read('https://qobuz.squid.wtf/api/download-music?track_id=356644700&quality=27');
  const url = r.json?.url || r.json?.data?.url;
  return { status: r.status, jsonKeys: Object.keys(r.json || {}), html: r.html, ...(typeof url === 'string' && url.startsWith('https://') ? await read(url, true) : {}) };
});
await probe('Lucida anonymous search', async () => {
  const r = await read('https://lucida.to/search?service=qobuz&country=NL&query=Blinding%20Lights');
  return { status: r.status, html: r.html, challenge: r.challenge, note: 'A webpage alone does not verify audio; no cookies or download jobs used.' };
});
await probe('Acedreamer public addon', async () => {
  const base = 'https://bitchord-monochrome-addon.onrender.com';
  const m = await read(base + '/manifest.json');
  const r = await read(base + '/search?q=Blinding%20Lights%20The%20Weeknd&quality=HI_RES_LOSSLESS');
  const t = r.json?.tracks?.find(t => /blinding lights/i.test(t.title) && /weeknd/i.test(t.artist));
  if (!t) return { manifest: m.json?.name, candidates: r.json?.tracks?.length || 0 };
  const s = await read(base + '/stream/' + encodeURIComponent(t.id) + '?quality=HI_RES_LOSSLESS');
  return { manifest: m.json?.name, ...(s.json?.url ? await read(s.json.url, true) : { error: 'No direct media URL' }) };
});
await probe('Free To Use API', async () => {
  const r = await read('https://api.freetouse.com/v3/music/tracks/all?limit=1');
  const data = r.json?.data, first = Array.isArray(data) ? data[0] : data;
  // Expose schema/format fields, never arbitrary response URLs or credentials.
  return { status: r.status, keys: Object.keys(r.json || {}), track: first ? Object.fromEntries(Object.entries(first).filter(([k,v]) => ['id','title','duration','format','audio_format','file_type'].includes(k) && typeof v !== 'object')) : null,
    trackKeys: first ? Object.keys(first) : [], mediaShapes: first ? Object.entries(first).filter(([k]) => /audio|file|download|stream/i.test(k)).map(([key,value]) => ({key,type:typeof value,keys:value && typeof value === 'object' ? Object.keys(value) : [], extension:typeof value==='string' ? value.split('?')[0].split('.').at(-1)?.slice(0,10):null})) : [] };
});
for (const q of ['mediatype:audio AND format:Flac AND collection:etree AND creator:"Billy Strings"', 'mediatype:audio AND format:Flac AND musopen']) await probe('Archive ' + (q.includes('etree') ? 'live recordings' : 'classical'), async () => {
  const url = new URL('https://archive.org/advancedsearch.php');
  Object.entries({ q, 'fl[]': 'identifier', rows: '2', output: 'json' }).forEach(([k,v]) => url.searchParams.set(k,v));
  const r = await read(url); const items = [];
  for (const d of r.json?.response?.docs || []) {
    const m = (await read('https://archive.org/metadata/' + encodeURIComponent(d.identifier))).json;
    const files = m?.files?.filter(f => /^flac$/i.test(f.format || '')) || [];
    const f = files[0];
    const item = { id: d.identifier, title: m?.metadata?.title, creator: m?.metadata?.creator, flacFiles: files.length, itemKeys: Object.keys(m?.metadata || {}), file: f ? { name: f.name, format: f.format, title: f.title, length: f.length, track: f.track, size: f.size } : null,
      derivative: f ? m.files.filter(x=>x.original===f.name).slice(0,2).map(x=>({format:x.format,title:x.title,length:x.length,original:x.original})) : [] };
    if (f) { try { Object.assign(item, await read('https://archive.org/download/' + encodeURIComponent(d.identifier) + '/' + f.name.split('/').map(encodeURIComponent).join('/'), true)); } catch(e) { item.error = /^HTTP \d+$/.test(e.message) ? e.message : 'FLAC header unavailable'; } }
    items.push(item);
  }
  return { status: r.status, total: r.json?.response?.numFound, items };
});
await writeFile('lossless-discovery-report.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
