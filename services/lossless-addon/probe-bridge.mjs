/** Public interfaces only. No account credentials, full audio downloads or signed URLs in reports. */
import { writeFile } from 'node:fs/promises';
import { flacInfo, createAddon } from './worker.mjs';
const report = { checkedAt: new Date().toISOString(), bridge: [], netease: [] };
const blocked = new Set();
async function read(address, media = false, redirect = 'follow') {
  const url = new URL(address);
  if (url.protocol !== 'https:' || blocked.has(url.host)) throw Error('HTTPS required or host cooling down');
  const r = await fetch(url, { redirect, signal: AbortSignal.timeout(12000), headers: {
    'User-Agent': 'OpenTune-Source-Research/1.0 (+https://github.com/justshivv/Opentune)', ...(media ? { Range: 'bytes=0-65535' } : {}),
  } });
  if (r.status === 429) blocked.add(url.host);
  if (r.status >= 300 && r.status < 400 && redirect === 'manual') { await r.body?.cancel(); return { status: r.status, location: r.headers.get('Location') }; }
  if (!r.ok) { await r.body?.cancel(); throw Error(`HTTP ${r.status}`); }
  const reader = r.body.getReader(), chunks = []; let size = 0; const cap = media ? 65536 : 1048576;
  try { for (;;) { const { value, done } = await reader.read(); if (done) break; const part = value.subarray(0, cap - size); chunks.push(part); size += part.length; if (size >= cap) break; } }
  finally { await reader.cancel().catch(() => {}); }
  const bytes = Buffer.concat(chunks);
  if (media) return { status: r.status, host: new URL(r.url).host, measured: flacInfo(bytes) };
  const text = bytes.toString('utf8');
  try { return { status: r.status, json: JSON.parse(text) }; }
  catch { return { status: r.status, html: true, links: [...text.matchAll(/(?:src|href)=["'](https?:\/\/[^"']+)["']/g)].map(m => { try { const u = new URL(m[1]); return u.origin + u.pathname; } catch { return ''; } }).filter(Boolean).slice(0,15) }; }
}
function safe(value) {
  if (Array.isArray(value)) return value.slice(0,30).map(safe);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([k]) => !/token|secret|cookie|authorization/i.test(k)).map(([k,v])=>[k,safe(v)]));
  if (typeof value === 'string') return value.replace(/https?:\/\/[^\s"'<>]+/g, s=>{ try { const u=new URL(s); return u.origin+u.pathname; } catch{return '[URL]';} }).slice(0,500);
  return value;
}
for (const path of ['/manifest.json','/sources','/health','/']) {
  if (blocked.has('losslessbridge.de.deplexo.com')) break;
  try { const r = await read('https://losslessbridge.de.deplexo.com'+path); report.bridge.push({path,...safe(r)}); }
  catch(e) {report.bridge.push({path,error:/^HTTP \d+$/.test(e.message)?e.message:'Network or response error'});}
}
for (const query of ['295 Sidhu Moose Wala','Blinding Lights The Weeknd']) {
  const result={query}; report.netease.push(result);
  try {
    const r=await read('https://music.163.com/api/cloudsearch/pc?'+new URLSearchParams({s:query,type:'1',limit:'5'}));
    result.searchStatus=r.status; result.code=r.json?.code;
    const tracks=r.json?.result?.songs || [];
    result.tracks=tracks.map(t=>({id:t.id,title:t.name,artists:(t.ar||t.artists||[]).map(a=>a.name),duration:(t.dt||t.duration)/1000,hires:!!t.hr,sq:!!t.sq}));
    const t=tracks.find(t=>query.startsWith('295') ? t.name==='295' : /blinding lights/i.test(t.name));
    if(!t) {result.note='No matching recording';continue;}
    const link=await read('https://api.qijieya.cn/meting/?'+new URLSearchParams({server:'netease',type:'url',id:String(t.id),br:'999000'}),false,'manual');
    result.linkStatus=link.status;
    const target=link.location;
    if(!target) {result.linkKeys=Object.keys(link.json||{});result.note='No redirect to audio';continue;}
    const u=new URL(target);result.mediaHost=u.host;
    if(u.protocol!=='https:' || !(u.hostname==='music.126.net'||u.hostname.endsWith('.music.126.net'))) {result.note='Not an HTTPS NetEase media host';continue;}
    Object.assign(result,await read(u,true));
  } catch(e) {result.error=/^HTTP \d+$/.test(e.message)?e.message:'Network or non-FLAC response';}
}
const adapter = createAddon();
report.opentuneAdapter = [];
for (const query of ['295 Sidhu Moose Wala', 'Blinding Lights The Weeknd']) {
  const r = await adapter(new Request('https://test.invalid/netease/search?q=' + encodeURIComponent(query)));
  const data = await r.json();
  const track = data.tracks?.find(t => query.startsWith('295') ? t.title === '295' && /sidhu/i.test(t.artist) : t.title === 'Blinding Lights' && /weeknd/i.test(t.artist));
  const result = { query, searchStatus: r.status, candidates: data.tracks?.length || 0 };
  if (track) {
    const stream = await adapter(new Request('https://test.invalid/netease/stream/' + track.id)); const audio = await stream.json();
    result.streamStatus = stream.status;
    if (stream.ok) result.measured = { codec: audio.codec, bitDepth: audio.bitDepth, sampleRate: audio.sampleRate, channels: audio.channels, duration: audio.duration };
    else result.error = audio.error;
  } else result.error = data.error || 'No matching recording';
  report.opentuneAdapter.push(result);
}
await writeFile('lossless-bridge-report.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
