import urllib.request, urllib.parse, re, json
root = "https://bppcaf.bot-keep.xyz/"
def get(url):
    req=urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 (compatible; OpenTuneResearch/1.0)"})
    try:
        with urllib.request.urlopen(req,timeout=20) as r:
            b=r.read(250001)
            return {"url":r.url,"status":r.status,"type":r.headers.get("Content-Type"),"body":b[:250000].decode("utf-8","replace")}
    except Exception as e: return {"url":url,"error":str(e)}
r=get(root)
print("PAGE",json.dumps(r,ensure_ascii=True)[:35000])
if "body" in r:
    scripts=re.findall(r'<script[^>]+src=["\x27]([^"\x27]+)',r["body"],re.I)
    for path in scripts[:8]:
        u=urllib.parse.urljoin(r["url"],path)
        if urllib.parse.urlparse(u).scheme != "https": continue
        s=get(u)
        print("SCRIPT",json.dumps(s,ensure_ascii=True)[:45000])
