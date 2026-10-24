import { apiBase, request, start, publishSources } from './session.mjs';
import { isoDay, presetRange, rangeError, dailyRows, legacyAnalytics } from './model.mjs';
const $ = id => document.getElementById(id);
const fmt = value => Number.isFinite(value) ? value.toLocaleString('en-US', { maximumFractionDigits: 1 }) : '—';
const dateLabel = day => new Date(day + 'T00:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const reportZone = () => $('report-zone').value;
const zoneLabel = () => reportZone() === 'Asia/Kolkata' ? 'IST' : 'UTC';
let apiPromise, summaryPromise, allController, periodController, periodData, selectedDay;
const node = (tag, text, className) => { const el = document.createElement(tag); if (text != null) el.textContent = text; if (className) el.className = className; return el; };
async function response(url, signal) {
  const result = await request(url, signal);
  if (!result.ok) { const error = new Error('HTTP ' + result.status); error.status = result.status; throw error; }
  return result.json();
}
function empty(id, message) { $(id).replaceChildren(node('p', message, 'empty')); }
function longLabel(value) {
  const text = String(value);
  if (text.length <= 44) return node('span', text);
  const details = node('details', null, 'long-label');
  const summary = node('summary', text.slice(0, 38) + '… ');
  const action = node('span', 'View more'); summary.append(action);
  details.append(summary, node('p', text));
  details.addEventListener('toggle', () => { action.textContent = details.open ? 'View less' : 'View more'; });
  return details;
}
function table(id, headings, rows, { daily = false } = {}) {
  const host = $(id); host.replaceChildren();
  if (!rows.length) { empty(id, 'No reports received for this period.'); return; }
  const tab = node('table'), caption = node('caption');
  caption.textContent = headings.join(', '); caption.hidden = true;
  const head = node('thead'), tr = node('tr'), body = node('tbody');
  headings.forEach((text, index) => { const th = node('th', text, index ? 'num' : ''); th.scope = 'col'; tr.append(th); });
  head.append(tr); tab.append(caption, head, body); host.append(tab);
  let expanded = false;
  const render = () => {
    body.replaceChildren();
    for (const row of (expanded ? rows : rows.slice(0, 7))) {
      const tr = node('tr');
      row.forEach((value, index) => {
        const td = node('td', null, index ? 'num' : '');
        if (index) td.textContent = fmt(value);
        else if (daily) { const button = node('button', dateLabel(value), 'date-link'); button.type = 'button'; button.addEventListener('click', () => selectDay(value)); td.append(button); }
        else td.append(longLabel(value));
        tr.append(td);
      });
      body.append(tr);
    }
  };
  render();
  if (rows.length > 7) {
    const button = node('button', `View more (${fmt(rows.length - 7)} more)`, 'view-more');
    button.type = 'button'; button.setAttribute('aria-expanded', 'false');
    button.addEventListener('click', () => { expanded = !expanded; render(); button.textContent = expanded ? 'View less' : `View more (${fmt(rows.length - 7)} more)`; button.setAttribute('aria-expanded', String(expanded)); });
    host.append(button);
  }
}
function selectDay(day) {
  selectedDay = day;
  const row = dailyRows(periodData).find(row => row.day === day);
  const detail = $('day-detail'); detail.replaceChildren(node('strong', dateLabel(day) + ' · ' + zoneLabel()));
  detail.append(node('span', row?.active == null ? ' — No activity history available for this date.' : ` — ${fmt(row.active)} active installations · ${fmt(row.plays)} plays · ${fmt(row.active ? row.plays / row.active : null)} plays per active installation`));
  for (const bar of $('chart').children) { const selected = bar.dataset.day === day; bar.classList.toggle('selected', selected); bar.setAttribute('aria-pressed', String(selected)); }
  const activeBar = $('chart').querySelector('.selected');
  if (activeBar) $('chart').scrollLeft = activeBar.offsetLeft - $('chart').clientWidth / 2 + activeBar.clientWidth / 2;
}
function chart() {
  if (!periodData) return;
  const metric = $('chart-metric').value, rows = dailyRows(periodData), max = Math.max(1, ...rows.map(row => row[metric] ?? 0));
  const host = $('chart'); host.replaceChildren();
  host.classList.toggle('chart-single', rows.length === 1);
  host.classList.toggle('chart-sparse', rows.length <= 7);
  for (const row of rows) {
    const value = row[metric], button = node('button', null, 'bar' + (value == null ? ' unknown' : ''));
    button.type = 'button'; button.dataset.day = row.day;
    const label = `${dateLabel(row.day)}: ${value == null ? 'unavailable' : fmt(value)} ${metric === 'plays' ? 'plays' : 'active installations'}`;
    button.title = label; button.setAttribute('aria-label', label);
    const fill = node('span', null, 'bar-fill'); fill.style.height = `${Math.max(3, (value ?? 0) / max * 118)}px`; fill.setAttribute('aria-hidden', 'true');
    const tick = node('span', rows.length === 1 ? dateLabel(row.day) : row.day.slice(5), 'bar-label'); tick.setAttribute('aria-hidden', 'true');
    if (rows.length <= 7) {
      const count = node('span', fmt(value), 'bar-value'); count.setAttribute('aria-hidden', 'true'); button.append(count);
    }
    button.append(fill, tick); button.addEventListener('click', () => selectDay(row.day)); host.append(button);
  }
  selectDay(selectedDay && rows.some(row => row.day === selectedDay) ? selectedDay : periodData.range.to);
}
async function loadPeriod() {
  const range = { from: $('from').value, to: $('to').value }, error = rangeError(range, new Date(), reportZone());
  $('filter-error').textContent = error; if (error) return;
  periodController?.abort(); const controller = new AbortController(); periodController = controller;
  const signal = controller.signal;
  periodData = null; selectedDay = null;
  $('period').setAttribute('aria-busy', 'true');
  $('period-title').textContent = range.from === range.to ? dateLabel(range.from) : `${dateLabel(range.from)} – ${dateLabel(range.to)}`;
  $('period-status').textContent = 'Loading selected dates…';
  for (const id of ['period-active', 'period-plays', 'period-average']) $(id).textContent = '—';
  for (const id of ['daily-table', 'versions-table', 'chart']) empty(id, 'Loading…');
  $('day-detail').textContent = 'Loading daily counts…';
  for (const button of document.querySelectorAll('[data-days]')) { const preset = presetRange(Number(button.dataset.days), new Date(), reportZone()); button.setAttribute('aria-pressed', String(preset.from === range.from && preset.to === range.to)); }
  try {
    const api = await apiPromise; let data;
    try { data = await response(`${api}/v1/analytics?${new URLSearchParams({...range,timeZone:reportZone()})}`, signal); }
    catch (e) { if (e.status !== 404 || reportZone() !== 'UTC') throw e; data = legacyAnalytics(await summaryPromise, range); }
    if (signal.aborted) return;
    if (reportZone() === 'Asia/Kolkata' && data.timeZone !== 'Asia/Kolkata') throw new Error('IST reporting needs the API update.');
    periodData = data;
    const hasHistory = data.coverageSince && range.to >= data.coverageSince;
    $('period-active').textContent = fmt(hasHistory ? data.totals.active : null);
    $('period-plays').textContent = fmt(hasHistory ? data.totals.plays : null);
    $('period-average').textContent = fmt(hasHistory && data.totals.active > 0 ? data.totals.plays / data.totals.active : null);
    $('period-status').textContent = data.limited ? 'Limited server: daily counts available; period-wide unique counts and version filters need the analytics API update.' : !hasHistory ? (reportZone() === 'Asia/Kolkata' ? 'No IST reports for these dates yet. Updated apps report IST; older counts remain in UTC history.' : 'No activity history available for these dates.') : `Reports available since ${dateLabel(data.coverageSince)}. Today and recent days may still change.`;
    table('daily-table', [`Date (${zoneLabel()})`, 'Active', 'Plays'], dailyRows(data).reverse().map(row => [row.day, row.active, row.plays]), { daily: true });
    if (data.versions) table('versions-table', ['Version', 'Active', 'Plays'], data.versions.map(row => [row.version, row.installations, row.plays]));
    else empty('versions-table', 'Date-filtered versions need the analytics API update.');
    chart();
  } catch (e) {
    if (signal.aborted) return;
    $('period-status').textContent = `Activity unavailable — not zero. ${e.message}`;
    for (const id of ['daily-table', 'versions-table', 'chart']) empty(id, 'Unavailable. Try refreshing data.');
    $('day-detail').textContent = 'Daily counts are unavailable.';
  } finally { if (!signal.aborted) $('period').setAttribute('aria-busy', 'false'); }
}
async function loadAll() {
  allController?.abort(); const controller = new AbortController(); allController = controller; const signal = controller.signal;
  $('refresh').disabled = true; $('updated').textContent = '';
  $('current-versions-note').textContent = 'Loading current versions…';
  $('lifetime-status').textContent = 'Loading totals…'; $('github-status').textContent = 'Loading GitHub totals…';
  for (const id of ['all-installs', 'all-plays', 'all-browsers', 'all-downloads', 'all-updates']) $(id).textContent = '—';
  for (const id of ['downloads-table', 'browser-table', 'current-versions-table']) empty(id, 'Loading…');
  apiPromise = Promise.resolve(apiBase);
  summaryPromise = apiPromise.then(api => response(api + '/v1/summary?' + new URLSearchParams({timeZone:reportZone()}), signal));
  await Promise.allSettled([
    loadPeriod(),
    summaryPromise.then(data => {
      if (signal.aborted) return;
      $('all-installs').textContent = fmt(data.totals.installs); $('all-plays').textContent = fmt(data.totals.plays); $('all-browsers').textContent = fmt(data.downloadBrowsers);
      // Older servers have no updates figure; show it only when present.
      $('all-updates').textContent = data.updates ? fmt(data.updates.total) : '—';
      $('lifetime-status').textContent = data.totals.since ? `Activity recorded since ${dateLabel(data.totals.since)}` : 'No activity reports received yet.';
      table('browser-table', ['Release', 'Browsers'], data.downloads.map(row => [row.release, row.browsers]));
      if (Array.isArray(data.currentVersions)) {
        // How many of each version's installations arrived by updating to it.
        const updatedTo = new Map((data.updates?.byVersion || []).map(row => [row.version, row.updates]));
        table('current-versions-table', ['Version', 'Installations', 'Updated to'],
          data.currentVersions.map(row => [row.version, row.installations, updatedTo.get(row.version) || 0]));
        const inferred = data.currentVersions.reduce((sum, row) => sum + row.inferred, 0);
        $('current-versions-note').textContent = `${fmt(data.totals.installs)} reporting installations in total. ${inferred ? `${fmt(inferred)} older installations estimated from daily history.` : 'Each installation is assigned to its latest reported version.'}`;
      } else {
        empty('current-versions-table', 'Current version counts need the API update.');
        $('current-versions-note').textContent = 'Historical version activity remains available above.';
      }
      $('updated').textContent = `Activity updated ${new Date(data.asOf).toLocaleString('en-GB', { timeZone: 'Asia/Kolkata' })} IST`;
    }).catch(() => { if (!signal.aborted) { $('lifetime-status').textContent = 'Reporting totals unavailable — not zero.'; empty('browser-table', 'Unavailable. Try refreshing data.'); empty('current-versions-table', 'Unavailable. Try refreshing data.'); $('current-versions-note').textContent = 'Current versions unavailable — not zero.'; } }),
    (async () => {
      try {
        const releases = [];
        for (let page = 1; ; page++) { const batch = await response(`https://api.github.com/repos/justshivv/Opentune/releases?per_page=100&page=${page}`, signal); releases.push(...batch.filter(release => !release.draft)); if (batch.length < 100) break; }
        if (signal.aborted) return;
        const rows = releases.map(release => [release.tag_name, release.assets.filter(asset => asset.name.toLowerCase().endsWith('.apk')).reduce((total, asset) => total + asset.download_count, 0)]);
        $('all-downloads').textContent = fmt(rows.reduce((total, row) => total + row[1], 0));
        $('github-status').textContent = `${fmt(rows.length)} published releases · cumulative APK downloads`;
        table('downloads-table', ['Release', 'Downloads'], rows);
      } catch { if (!signal.aborted) { $('github-status').textContent = 'GitHub unavailable — not zero.'; empty('downloads-table', 'Download counts could not be loaded. Try again later.'); } }
    })(),
  ]);
  if (!signal.aborted) $('refresh').disabled = false;
}
$('filters').addEventListener('submit', event => { event.preventDefault(); loadPeriod(); });
for (const button of document.querySelectorAll('[data-days]')) button.addEventListener('click', () => { const range = presetRange(Number(button.dataset.days), new Date(), reportZone()); $('from').value = range.from; $('to').value = range.to; loadPeriod(); });
$('chart-metric').addEventListener('change', chart);
$('refresh').addEventListener('click', loadAll);
function updateCalendar() {
  $('from').max = $('to').max = isoDay(new Date(), reportZone());
  document.querySelectorAll('.report-zone-label').forEach(label => { label.textContent = zoneLabel(); });
}
$('report-zone').addEventListener('change', () => {
  updateCalendar();
  const range = presetRange(30, new Date(), reportZone()); $('from').value = range.from; $('to').value = range.to;
  loadAll();
});
updateCalendar();
const initial = presetRange(30, new Date(), reportZone()); $('from').value = initial.from; $('to').value = initial.to;
document.querySelector('.about').addEventListener('toggle', event => { event.currentTarget.querySelector('summary span').textContent = event.currentTarget.open ? 'View less' : 'View more'; });
let addonDraft=null;
const addonStatus=$('addon-status');
function renderAddons(){
  const host=$('addon-editor');host.replaceChildren();
  if(!addonDraft)return;
  addonDraft.sources.forEach((source,index)=>{
    const on=source.enabled!==false;
    const row=node('div',null,'addon-row'+(on?'':' addon-off'));
    row.append(node('strong',source.name),node('p',on?'On · apps use it':'Off · apps send it nothing','addon-state'),node('p',source.url,'footnote'));
    // Turning a source on or off publishes straight away, so it takes effect without a second step.
    const toggle=node('button',on?'Turn off':'Turn on');toggle.type='button';toggle.setAttribute('aria-pressed',String(on));
    toggle.onclick=()=>{source.enabled=!on;publishAddons(source.name+(on?' turned off':' turned on'));};row.append(toggle);
    for(const [label,delta] of [['Move up',-1],['Move down',1]]){const b=node('button',label);b.type='button';b.disabled=index+delta<0||index+delta>=addonDraft.sources.length;b.onclick=()=>{const s=addonDraft.sources.splice(index,1)[0];addonDraft.sources.splice(index+delta,0,s);renderAddons();};row.append(b);}
    const remove=node('button','Remove');remove.type='button';remove.onclick=()=>{addonDraft.sources.splice(index,1);renderAddons();};row.append(remove);host.append(row);
  });
  $('addon-publish').disabled=false;
}
async function loadAddons(){
  try{addonDraft=await response(apiBase+'/v1/addons',new AbortController().signal);renderAddons();addonStatus.textContent='Published revision '+addonDraft.revision+' loaded. Turning a source on or off publishes at once; other changes stay in draft until you publish.';}
  catch{addonStatus.textContent='Could not load sources. Deploy the updated Worker and database schema first.';}
}
async function publishAddons(what){
  if(!addonDraft)return;$('addon-publish').disabled=true;
  try{addonDraft=await publishSources(addonDraft);addonStatus.textContent=(what?what+'. ':'')+'Published revision '+addonDraft.revision+'. Apps pick it up within a few minutes of being opened.';renderAddons();}
  catch(e){addonStatus.textContent=e.message;$('addon-publish').disabled=false;if(what)loadAddons();}
}
$('addon-load').onclick=loadAddons;
$('addon-form').onsubmit=event=>{event.preventDefault();if(!addonDraft){addonStatus.textContent='Load the catalog first.';return;}if(addonDraft.sources.length>=20){addonStatus.textContent='Up to 20 sources are allowed.';return;}addonDraft.sources.push({id:crypto.randomUUID(),name:$('addon-name').value.trim(),url:$('addon-url').value.trim(),enabled:true});event.currentTarget.reset();renderAddons();};
$('addon-publish').onclick=()=>publishAddons();
start(()=>{loadAll();loadAddons();});
