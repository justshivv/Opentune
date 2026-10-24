import { writeFile } from 'node:fs/promises';
import { flacInfo } from '../services/lossless-addon/worker.mjs';
const UA = 'OpenTune-Source-Research/1.0 (+https://github.com/justshivv/Opentune)';
const sources = [
  {name:'Jumo',base:'https://jumo-dl.pages.dev',jumo:true},
  {name:'Monokenny',base:'https://qobuz.kennyy.com.br'},
  {name:'Squid',base:'https://qobuz.squid.wtf'}
];
function safeError(e) { return {error:e.name,code:e.cause?.code || e.code || 'NETWORK_OR_TIMEOUT'}; }
async function read(url, media=false) {
  let u=new URL(url);
  for(let n=0;n<4;n++) {
    if(u.protocol!=='https:'||u.username||u.password||u.port) throw Error('Unsafe URL');
    if(media && !['qobuz.com','qbz.io','akamaized.net'].some(d=>u.hostname===d||u.hostname.endsWith('.'+d))) return {status:'unverified_media_host',host:u.hostname};
    const r=await fetch(u,{redirect:'manual',headers:{'User-Agent':UA,Accept:media?'audio/flac,*/*':'application/json,text/html;q=0.8',...(media?{Range:'bytes=0-65535'}:{})},signal:AbortSignal.timeout(12000)});
    if(r.status>=300&&r.status<400&&r.headers.get('location')) {await r.body?.cancel();u=new URL(r.headers.get('location'),u);continue;}
    const reader=r.body?.getReader(),chunks=[];let size=0,cap=media?65536:1048576;
    if(reader)try{while(size<cap){const {value,done}=await reader.read();if(done)break;const b=value.subarray(0,cap-size);chunks.push(b);size+=b.length;}}finally{await reader.cancel().catch(()=>{});}
    const bytes=Buffer.concat(chunks),text=bytes.toString('utf8');
    const out={status:r.status,host:u.hostname,type:r.headers.get('content-type'),bytesRead:size};
    if(media){try{out.flac=flacInfo(bytes);}catch{out.nativeFlac=false;}return out;}
    try{out.json=JSON.parse(text);}catch{
      out.html=/<html|<!doctype/i.test(text);
      out.challenge=/cf-chl|captcha|just a moment|verify.{0,20}human/i.test(text);
      out.title=text.match(/<title[^>]*>([^<]*)<\/title>/i)?.[1]?.slice(0,120);
      out.scripts=[...text.matchAll(/<script[^>]+src=["']([^"']+)["']/gi)].map(m=>m[1].split('?')[0]).slice(0,8);
    }
    return out;
  }
  return {error:'Too many redirects'};
}
function summary(r) {
  const {json,...rest}=r;
  if(json) {
    rest.jsonKeys=Object.keys(json).slice(0,20);
    const msg=json.error||json.message;
    if(typeof msg==='string')rest.message=msg.replace(/https?:\/\/\S+/g,'[URL]').replace(/(?:token|grant|key|secret)=[^\s&]+/gi,'[redacted]').slice(0,160);
    if(json.success!==undefined)rest.success=json.success;
    if(json.previewDetected!==undefined)rest.previewDetected=json.previewDetected;
  }
  return rest;
}
function items(j) {
  const c=[j?.data?.tracks?.items,j?.tracks?.items,j?.data?.items,j?.items,j?.tracks,j?.data];
  return c.find(Array.isArray)||[];
}
const report={checkedAt:new Date().toISOString(),scope:'Anonymous requests, no credentials or challenge bypass; bounded FLAC headers, not decoded playback',results:[]};
await Promise.all(sources.map(async s=>{
 const result={name:s.name,base:s.base,searches:[],streams:[]};report.results.push(result);
 try{result.home=summary(await read(s.base+'/'));}catch(e){result.home=safeError(e);}
 let match;
 for(const q of ['295 Sidhu Moose Wala','Blinding Lights The Weeknd']) {
  const url=new URL(s.jumo?'/search':'/api/get-music',s.base);
  url.searchParams.set(s.jumo?'query':'q',q);url.searchParams.set('offset','0');
  if(s.jumo){url.searchParams.set('region','US');url.searchParams.set('limit','5');}
  try{
   const r=await read(url);const tracks=items(r.json);
   const found=tracks.filter(t=>q.startsWith('295')?/295/i.test(t.title||t.name||''):/blinding lights/i.test(t.title||t.name||'')).slice(0,3);
   result.searches.push({query:q,...summary(r),matches:found.map(t=>({id:t.id,title:t.title||t.name,artist:t.performer?.name||t.artist?.name||t.artist,duration:t.duration,bitDepth:t.maximum_bit_depth,sampleRate:t.maximum_sampling_rate}))});
   match ||= found.find(t=>/sidhu|moose|weeknd/i.test(t.performer?.name||t.artist?.name||t.artist||''));
   if([403,429,503].includes(r.status)||r.challenge)break;
  }catch(e){result.searches.push({query:q,...safeError(e)});break;}
 }
 // Known public catalogue ID allows distinguishing a search outage from resolve access.
 const id=match?.id||'356644700';
 for(const quality of [27,6]) {
  const url=new URL(s.jumo?'/fetch':'/api/download-music',s.base);
  url.searchParams.set('track_id',String(id));url.searchParams.set(s.jumo?'format_id':'quality',String(quality));
  if(s.jumo)url.searchParams.set('region','US');
  try{
   const r=await read(url),j=r.json;
   const attempt={trackId:id,fromSearch:!!match,quality,...summary(r)};
   const media=j?.directUrl||j?.url||j?.data?.url;
   if(typeof media==='string'&&media.startsWith('https://')&&r.status===200&&!j.previewDetected) {
    attempt.media=await read(media,true);
    if(attempt.media.flac&&match?.duration)attempt.durationDifferenceSeconds=Math.abs(attempt.media.flac.duration-match.duration);
   }
   result.streams.push(attempt);
   if([401,403,429,503].includes(r.status)||r.challenge||/captcha|verify|verification/i.test(attempt.message||''))break;
  }catch(e){result.streams.push({trackId:id,quality,...safeError(e)});break;}
 }
}));
await writeFile('qobuz-source-check.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
