"""Read-only protocol probes; never log signed audio URLs or URL path tokens."""
import json, urllib.request, urllib.parse, time, pathlib, shutil, subprocess
ROOTS = [
    ('asphyx-worker', 'https://asphyx-lossless-addon.xyzuan1608.workers.dev'),
    ('asphyx-vercel', 'https://asphyx-addon-vercel.vercel.app'),
    ('citrus', 'https://citruslossless.fanzvengeance10.workers.dev'),
]
out=pathlib.Path('build/addon-probes');out.mkdir(parents=True,exist_ok=True)
def get(url, limit=1048576):
    req=urllib.request.Request(url,headers={'User-Agent':'OpenTune-Addon-Compatibility/1.0','Accept':'application/json'})
    with urllib.request.urlopen(req,timeout=18) as response:
        data=response.read(limit+1)
        if len(data)>limit:raise ValueError('oversized response')
        return json.loads(data)
def safe(value):
    if isinstance(value,dict):return {k:safe(v) for k,v in value.items() if k.lower() not in ('headers','token','authorization','secret','cookie')}
    if isinstance(value,list):return [safe(v) for v in value[:3]]
    if isinstance(value,str) and ('://' in value or len(value)>500):return '[redacted URL or long value]'
    return value
for name,base in ROOTS:
    report={'name':name}
    try:
        started=time.monotonic()
        manifest=get(base+'/manifest.json');report['manifest']=safe(manifest)
        report['manifestMs']=round((time.monotonic()-started)*1000)
        report['publicSourceLinks']=[v for v in manifest.values() if isinstance(v,str) and v.startswith('https://github.com/') and not urllib.parse.urlparse(v).query]
        params={s['key']:s['default'] for s in manifest.get('settings',[]) if 'key' in s and isinstance(s.get('default'),(str,int,float,bool))}
        # Respect the declared lossless tier when one exists.
        setting=next((s for s in manifest.get('settings',[]) if s.get('key')=='quality'),{})
        choices=[str(o.get('value','')) for o in setting.get('options',[])]
        params['quality']=next((x for x in choices if 'lossless' in x.lower() or 'hi_res' in x.lower()),'LOSSLESS')
        for query in ['Wo Noor AP Dhillon','Blinding Lights The Weeknd']:
            started=time.monotonic()
            result=get(base+'/search?'+urllib.parse.urlencode(dict(params,q=query)))
            tracks=result.get('tracks',[]);report.setdefault('searches',[]).append({'query':query,'count':len(tracks),'elapsedMs':round((time.monotonic()-started)*1000),'examples':safe(tracks[:2])})
            matches=[t for t in tracks if str(t.get('title','')).lower() in ('wo noor','blinding lights') and ('dhillon' in str(t.get('artist','')).lower() or 'weeknd' in str(t.get('artist','')).lower())]
            if matches:
                track=matches[0]
                started=time.monotonic()
                stream=get(base+'/stream/'+urllib.parse.quote(str(track['id']),safe='')+'?'+urllib.parse.urlencode(params))
                report['stream']=safe(stream)
                report['streamMs']=round((time.monotonic()-started)*1000)
                url=stream.get('url')
                if url and urllib.parse.urlparse(url).scheme=='https' and not stream.get('encrypted',False):
                    report['streamHost']=urllib.parse.urlparse(url).hostname
                    report['embeddedUpstreamHosts']=sorted({urllib.parse.urlparse(v).hostname for values in urllib.parse.parse_qs(urllib.parse.urlparse(url).query).values() for v in values if v.startswith('https://')})
                    req=urllib.request.Request(url,headers={'Range':'bytes=0-65535','User-Agent':'OpenTune-Addon-Compatibility/1.0'})
                    with urllib.request.urlopen(req,timeout=18) as response:
                        prefix=response.read(65536)
                        report['media']={'host':urllib.parse.urlparse(response.geturl()).hostname,'status':response.status,'contentType':response.headers.get('Content-Type'),'bytesRead':len(prefix),'flac':prefix.startswith(b'fLaC'),'mp4':b'ftyp' in prefix[:32],'dash':b'<MPD' in prefix[:2048]}
                        if prefix.startswith(b'#EXTM3U'):
                            lines=prefix.decode().splitlines()
                            report['hlsTags']=[line.split(':',1)[0] for line in lines if line.startswith('#')][:30]
                            segment=next((line for line in lines if line and not line.startswith('#')),None)
                            if segment:
                                address=urllib.parse.urljoin(url,segment)
                                if urllib.parse.urlparse(address).scheme=='https':
                                    with urllib.request.urlopen(urllib.request.Request(address,headers={'Range':'bytes=0-4095'}),timeout=18) as seg:
                                        data=seg.read(4096)
                                        report['segment']={'flac':data.startswith(b'fLaC'),'mp4':b'ftyp' in data[:32] or b'styp' in data[:32],'playlist':data.startswith(b'#EXTM3U'),'mpegts':data[:1]==b'G'}
                    if shutil.which('ffprobe'):
                        try:
                            decoded=subprocess.run(['ffprobe','-v','error','-read_intervals','%+1','-show_entries','stream=codec_name,sample_rate,channels,bits_per_raw_sample','-of','json',url],capture_output=True,text=True,timeout=25)
                            report['decoder']=safe(json.loads(decoded.stdout)) if decoded.returncode==0 else {'error':'Media probe failed','exitCode':decoded.returncode}
                        except Exception:report['decoder']={'error':'Media probe did not complete'}
                break
    except Exception as error:report['error']=type(error).__name__+(': HTTP '+str(error.code) if hasattr(error,'code') else '')
    (out/(name+'.json')).write_text(json.dumps(report,indent=2))
    print('ADDON_PROBE '+json.dumps(report),flush=True)

# Independently test the public Track Streamer interface already used by our
# adapter. A matching catalogue is evidence of compatibility, not proof of the
# private implementation of any third-party proxy.
report={'name':'opentune-direct-monochrome','upstream':'tracks.monochrome.st'}
try:
    started=time.monotonic()
    result=get('https://tracks.monochrome.st/search/tracks?'+urllib.parse.urlencode({'q':'Wo Noor AP Dhillon','limit':20}))
    tracks=result.get('tracks',[])
    report['searchMs']=round((time.monotonic()-started)*1000)
    report['count']=len(tracks)
    report['examples']=safe(tracks[:2])
    track=next((t for t in tracks if str(t.get('title','')).lower()=='wo noor' and 'dhillon' in json.dumps(t.get('artistNames',t.get('artists',[]))).lower()),None)
    if track:
        track_id=str(track.get('trackId',track.get('id','')))
        report['trackId']=track_id
        started=time.monotonic()
        req=urllib.request.Request('https://tracks.monochrome.st/track/'+urllib.parse.quote(track_id,safe=''),headers={'Range':'bytes=0-41','User-Agent':'OpenTune-Addon-Compatibility/1.0'})
        with urllib.request.urlopen(req,timeout=18) as response:
            prefix=response.read(42)
            report['media']={'host':urllib.parse.urlparse(response.geturl()).hostname,'status':response.status,'bytesRead':len(prefix),'flac':prefix.startswith(b'fLaC'),'elapsedMs':round((time.monotonic()-started)*1000)}
            if len(prefix)==42 and prefix[:4]==b'fLaC' and prefix[4]&127==0 and prefix[5:8]==b'\x00\x00\x22':
                packed=int.from_bytes(prefix[18:26],'big')
                rate=packed>>44
                report['media'].update(sampleRate=rate,channels=((packed>>41)&7)+1,bitDepth=((packed>>36)&31)+1,duration=(packed&((1<<36)-1))/rate if rate else 0)
except Exception as error:report['error']=type(error).__name__+(': HTTP '+str(error.code) if hasattr(error,'code') else '')
(out/'opentune-direct-monochrome.json').write_text(json.dumps(report,indent=2))
print('ADDON_PROBE '+json.dumps(report),flush=True)

