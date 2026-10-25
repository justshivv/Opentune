import urllib.request, urllib.parse, urllib.error, json, struct
ROOT="https://bppcaf.bot-keep.xyz"
def safe(v):
    if isinstance(v,dict): return {k:safe(x) for k,x in v.items() if not any(z in k.lower() for z in ["token","secret","authorization","cookie"])}
    if isinstance(v,list): return [safe(x) for x in v[:4]]
    if isinstance(v,str) and v.startswith(("http://","https://")):
        p=urllib.parse.urlsplit(v)
        return {"host":p.hostname,"path":p.path[:200],"hasQuery":bool(p.query)}
    return v
def get(path):
    u=ROOT+path
    try:
        with urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"BitChord/v1.8.1"}),timeout=25) as r:
            body=r.read(250000).decode("utf-8","replace")
            try: data=json.loads(body)
            except: data={"text":body[:500]}
            return r.status,data
    except urllib.error.HTTPError as e: return e.code,{"error":e.read(500).decode("utf-8","replace")}
    except Exception as e: return None,{"error":str(e)}
st,m=get("/manifest.json")
print("MANIFEST",st,json.dumps(safe(m),ensure_ascii=True))
st,d=get("/search?"+urllib.parse.urlencode({"q":"295 Sidhu Moose Wala","quality":"lossless"}))
print("SEARCH",st,json.dumps(safe(d),ensure_ascii=True)[:12000])
tracks=d.get("tracks",[]) if isinstance(d,dict) else []
if tracks:
    t=tracks[0]
    st,s=get("/stream/"+urllib.parse.quote(str(t["id"]),safe="")+"?quality=lossless")
    print("STREAM",st,json.dumps(safe(s),ensure_ascii=True)[:12000])
    u=s.get("url") if isinstance(s,dict) else None
    if u and urllib.parse.urlsplit(u).scheme=="https":
        try:
            req=urllib.request.Request(u,headers={"Range":"bytes=0-65535","User-Agent":"Mozilla/5.0"})
            with urllib.request.urlopen(req,timeout=30) as r:
                b=r.read(65536)
                out={"status":r.status,"type":r.headers.get("Content-Type"),"finalHost":urllib.parse.urlsplit(r.url).hostname,"bytesInspected":len(b),"signature":b[:4].hex(),"nativeFlac":b[:4]==b"fLaC"}
                if b[:4]==b"fLaC" and len(b)>=42 and b[4]&127==0:
                    n=int.from_bytes(b[18:26],"big")
                    rate=n>>44
                    out.update(sampleRate=rate,channels=((n>>41)&7)+1,bitDepth=((n>>36)&31)+1,duration=(n&((1<<36)-1))/rate if rate else None)
                print("AUDIO",json.dumps(out))
        except Exception as e: print("AUDIO_ERROR",type(e).__name__,str(e)[:250])
