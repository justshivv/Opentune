const $ = id => document.getElementById(id);
const fmt = n => Number.isFinite(n) ? n.toLocaleString('en-US') : '—';
const kpis = ['dau','wau','mau','installs','plays-today','plays','per','dl','browsers'];
function table(element, rows) {
  element.replaceChildren();
  for (const [label,value] of rows) {
    const row=document.createElement('tr'), a=document.createElement('td'), b=document.createElement('td');
    a.textContent=label; b.textContent=fmt(value); row.append(a,b); element.append(row);
  }
}
function empty(element,text) { element.textContent=text; }
async function response(url) {
  const r=await fetch(url,{cache:'no-store',signal:AbortSignal.timeout(12000)});
  if(!r.ok) throw new Error('HTTP '+r.status);
  return r.json();
}
async function loadUsage() {
  $('refresh').disabled=true;
  kpis.forEach(key=>$('k-'+key).textContent='—');
  ['daily','dailyPlays','versions','unique-downloads','downloads'].forEach(id=>empty($(id),'Loading…'));
  const messages=[];
  await Promise.all([
    (async()=>{
      try {
        const config=await response('../usage-config.json');
        if(!config.apiBase) throw new Error('Unique counting is not connected yet. Historical counter totals cannot identify unique installations.');
        const api=new URL(config.apiBase);
        if(api.protocol!=='https:' || api.username || api.password) throw new Error('Invalid usage API configuration');
        const data=await response(api.href.replace(/\/$/,'')+'/v1/summary');
        $('k-dau').textContent=fmt(data.today.active); $('k-wau').textContent=fmt(data.week); $('k-mau').textContent=fmt(data.month);
        $('k-installs').textContent=fmt(data.totals.installs); $('k-plays').textContent=fmt(data.totals.plays);
        $('k-plays-today').textContent=fmt(data.today.plays);
        $('k-per').textContent=data.today.active ? (data.today.plays/data.today.active).toFixed(1) : '—';
        $('k-browsers').textContent=fmt(data.downloadBrowsers);
        table($('versions'),data.versions.map(d=>[d.version,d.installations]));
        table($('unique-downloads'),data.downloads.map(d=>['v'+d.release,d.browsers]));
        const daily=document.createElement('table'), plays=document.createElement('table');
        table(daily,data.daily.map(d=>[d.day,d.active])); table(plays,data.daily.map(d=>[d.day,d.plays]));
        $('daily').replaceChildren(daily); $('dailyPlays').replaceChildren(plays);
        if(!data.daily.length) { empty($('daily'),'No installation reports received yet.'); empty($('dailyPlays'),'No play reports received yet.'); }
        messages.push('Installation reports as of '+new Date(data.asOf).toLocaleTimeString());
      } catch(e) {
        messages.push(e.message);
        ['daily','dailyPlays','versions','unique-downloads'].forEach(id=>empty($(id),'Unavailable — not zero.'));
      }
    })(),
    (async()=>{
      try {
        // Follow all release pages; totals aren't silently limited to the latest releases.
        let page=1, all=[];
        while(true) {
          const part=await response('https://api.github.com/repos/justshivv/Opentune/releases?per_page=100&page='+page);
          all.push(...part.filter(r=>!r.draft)); if(part.length<100) break; page++;
        }
        const rows=all.map(r=>[r.tag_name,r.assets.filter(a=>a.name.endsWith('.apk')).reduce((sum,a)=>sum+a.download_count,0)]);
        $('k-dl').textContent=fmt(rows.reduce((sum,r)=>sum+r[1],0)); table($('downloads'),rows);
      } catch { messages.push('GitHub download totals are unavailable.'); empty($('downloads'),'Unavailable — not zero.'); }
    })(),
  ]);
  $('when').textContent=messages.join(' · '); $('refresh').disabled=false;
}
$('refresh').addEventListener('click',loadUsage);
loadUsage();
