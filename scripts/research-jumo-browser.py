import json, os, re, time
from playwright.sync_api import sync_playwright
report={'checkedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'network':[]}
def public_path(url):
    from urllib.parse import urlsplit
    u=urlsplit(url)
    path=re.sub(r'/[A-Za-z0-9_-]{25,}', '/[opaque]', u.path)
    return u.scheme+'://'+u.netloc+path
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    page=browser.new_page()
    def response(r):
        if r.request.resource_type not in ['xhr','fetch']: return
        item={'endpoint':public_path(r.url),'status':r.status,'type':r.headers.get('content-type')}
        try:
            j=r.json()
            if isinstance(j,dict):
                item['keys']=list(j)[:20]
                for k in ['error','message','success','previewDetected']:
                    v=j.get(k)
                    if isinstance(v,(str,bool)):
                        item[k]=re.sub(r'https?://\\S+','[URL]',str(v))[:160]
                data=j.get('data')
                if isinstance(data,dict): item['dataKeys']=list(data)[:20]
        except Exception: pass
        if len(report['network'])<30: report['network'].append(item)
    page.on('response',response)
    try:
        page.goto('https://jumo-dl.pages.dev/',wait_until='domcontentloaded',timeout=25000)
        page.wait_for_timeout(4000)
        visible=page.locator('input:visible')
        report['inputs']=[{'type':el.get_attribute('type'),'placeholder':el.get_attribute('placeholder'),'id':el.get_attribute('id')} for el in visible.all()]
        body=page.locator('body').inner_text()
        report['challenge']=bool(re.search(r'verify you are human|checking your browser|just a moment',body,re.I))
        if not report['challenge']:
            candidates=page.locator('input[type="search"]:visible, input[type="text"]:visible, input:not([type]):visible')
            if candidates.count():
                box=candidates.first
                box.fill('295 Sidhu Moose Wala')
                page.wait_for_timeout(3500)
                box.press('Enter')
                page.wait_for_timeout(5000)
                report['visibleResult']=page.locator('body').inner_text()[-1600:]
        report['pageTitle']=page.title()
    except Exception as e: report['error']=type(e).__name__
    browser.close()
with open('jumo-browser-check.json','w') as f: json.dump(report,f,indent=2)
print(json.dumps(report,indent=2))
