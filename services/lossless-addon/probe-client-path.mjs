import { flacInfo } from './worker.mjs';
const base = 'https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev';
const report = { checkedAt: new Date().toISOString(), scope: 'Unsigned staged-service path and bounded media headers, not genuine Android attestation', results: [] };
async function request(url, media = false) {
  const start = Date.now();
  const r = await fetch(url, { signal: AbortSignal.timeout(8000), headers: { 'User-Agent': 'OpenTune-Client-Diagnostics/1.0', ...(media ? { Range: 'bytes=0-65535' } : {}) } });
  if (!r.ok) { await r.body?.cancel(); throw Error(`HTTP ${r.status}`); }
  const reader = r.body.getReader(), parts = []; let size = 0; const cap = media ? 65536 : 2097152;
  try { while (size < cap) { const p = await reader.read(); if (p.done) break; const b = p.value.subarray(0, cap-size); parts.push(b); size += b.length; } }
  finally { await reader.cancel().catch(()=>{}); }
  const bytes = new Uint8Array(size); let offset=0; for (const p of parts) { bytes.set(p, offset); offset+=p.length; }
  return { milliseconds: Date.now()-start, data: media ? flacInfo(bytes) : JSON.parse(new TextDecoder().decode(bytes)) };
}
try { report.policy = (await request(base + '/auth/policy')).data.mode; } catch { report.policy = 'unavailable'; }
for (const title of ['295', 'Same Beef', 'So High']) {
  const r = { title, artist: 'Sidhu Moose Wala', stages: [] }; const start=Date.now();
  try {
    const m=await request(base+'/monochrome/manifest.json'); r.stages.push({ stage:'manifest', milliseconds:m.milliseconds });
    const s=await request(base+'/monochrome/search?quality=best&q='+encodeURIComponent(title+' Sidhu Moose Wala'));
    r.stages.push({ stage:'search', milliseconds:s.milliseconds });
    const t=s.data.tracks.find(t=>t.title.toLowerCase()===title.toLowerCase()&&/sidhu/i.test(t.artist));
    if(!t) throw Error('No matching recording');
    const f=await request(base+'/monochrome/stream/'+encodeURIComponent(t.id)+'?quality=best');
    r.stages.push({ stage:'stream', milliseconds:f.milliseconds });
    const media=await request(f.data.url,true); r.stages.push({ stage:'device-style media probe', milliseconds:media.milliseconds });
    r.measured=media.data;
  } catch(e) { r.error=/^HTTP \d+$|^No matching recording$/.test(e.message)?e.message:'Network, timeout or invalid response'; }
  r.totalMilliseconds=Date.now()-start; report.results.push(r);
}
console.log(JSON.stringify(report,null,2));
