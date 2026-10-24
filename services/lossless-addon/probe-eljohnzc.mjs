/** Public-host inspection only. Never commit configured URLs, keys or returned media URLs. */
import { writeFile } from 'node:fs/promises';
import { flacInfo } from './worker.mjs';
const origin='https://eljohnzcaddon.velixir.run';
const report={checkedAt:new Date().toISOString(),host:new URL(origin).host,scope:'Public entry points only; user-specific configuration URL not tested.',pages:[]};
let blocked=false;
async function read(url,media=false) {
  const r=await fetch(url,{signal:AbortSignal.timeout(12000),redirect:'manual',headers:{'User-Agent':'OpenTune-Source-Research/1.0',...(media?{Range:'bytes=0-65535'}:{})}});
  if(r.status===429) blocked=true;
  const status=r.status, type=r.headers.get('Content-Type');
  if(!r.ok){await r.body?.cancel();return {status,type};}
  const reader=r.body.getReader(),chunks=[];let n=0;const cap=media?65536:1048576;
  try{for(;;){const {value,done}=await reader.read();if(done)break;const part=value.subarray(0,cap-n);chunks.push(part);n+=part.length;if(n>=cap)break;}}
  finally{await reader.cancel().catch(()=>{});}
  const bytes=Buffer.concat(chunks);if(media){try{return {status,measured:flacInfo(bytes)};}catch{return {status,nativeFlac:false};}}
  const text=bytes.toString('utf8');try{return {status,type,json:JSON.parse(text)};}catch{return {status,type,text};}
}
let manifest;
for(const path of ['/','/manifest.json','/sources','/health']){
  if(blocked)break;
  try{
    const r=await read(origin+path),row={path,status:r.status,type:r.type};
    if(r.json){
      row.keys=Object.keys(r.json);
      if(r.json.id&&Array.isArray(r.json.resources)){
        row.manifest={name:r.json.name,version:r.json.version,resources:r.json.resources,allowDownloads:r.json.allowDownloads,checkValidLossless:r.json.checkValidLossless,
          qualities:r.json.settings?.find(s=>s.key==='quality')?.options?.map(o=>({label:o.label,value:o.value}))};
        manifest=r.json;
      }
    }
    if(r.text){
      row.title=/<title[^>]*>([^<]*)/i.exec(r.text)?.[1]?.slice(0,150);
      row.challenge=/cf-chl|just a moment|captcha/i.test(r.text);
      row.mentionedSources=['monochrome','tidal','qobuz','deezer','amazon','netease','gdstudio','archive','spotify'].filter(x=>r.text.toLowerCase().includes(x));
      row.publicRepositories=[...r.text.matchAll(/https:\/\/github\.com\/[\w.-]+\/[\w.-]+/g)].map(m=>m[0]).filter((x,i,a)=>a.indexOf(x)===i).slice(0,10);
    }
    report.pages.push(row);
  }catch{report.pages.push({path,error:'Network or timeout'});}
}
if(manifest&&!blocked){
  const parameters=new URLSearchParams();
  for(const s of manifest.settings||[])if(['string','number','boolean'].includes(typeof s.default))parameters.set(s.key,String(s.default));
  const qualities=manifest.settings?.find(s=>s.key==='quality')?.options?.map(o=>String(o.value))||[];
  parameters.set('quality',qualities.find(x=>/hi.?res|best|max/i.test(x))||qualities.find(x=>/lossless|flac/i.test(x))||'LOSSLESS');
  parameters.set('q','295 Sidhu Moose Wala');
  try{
    const r=await read(origin+'/search?'+parameters);const t=r.json?.tracks?.find(t=>t.title==='295'&&/sidhu/i.test(t.artist));
    report.playback={searchStatus:r.status,candidates:r.json?.tracks?.length||0,matchingRecording:!!t};
    if(t){parameters.delete('q');const s=await read(origin+'/stream/'+encodeURIComponent(t.id)+'?'+parameters);report.playback.streamStatus=s.status;
      report.playback.declared={codec:s.json?.codec,bitDepth:s.json?.bitDepth,sampleRate:s.json?.sampleRate};
      if(s.json?.url&&!(s.json.encrypted&&s.json.encrypted!=='false')){
        const u=new URL(s.json.url);if(u.protocol==='https:'){report.playback.mediaHost=u.host;Object.assign(report.playback,await read(u,true));}
      }
    }
  }catch{report.playback={...report.playback,error:'Network or unsupported response'};}
}
await writeFile('eljohnzc-public-report.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
