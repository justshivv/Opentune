import { catalog, publishCatalog, readCatalogBody } from './addons.mjs';
import { authorized, locked, privateHeaders, authRoute } from './auth.mjs';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const VERSION = /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]{1,32})?$/;
const DAY = 86400000;
export const activitySql = `INSERT INTO activity(installation,day,version,plays,received_at) VALUES(?,?,?,?,?)
 ON CONFLICT(installation,day,version) DO UPDATE SET plays=excluded.plays,received_at=excluded.received_at
 WHERE excluded.plays > activity.plays`;
const istActivitySql = activitySql.replaceAll('activity', 'activity_ist');
const allActivity = '(SELECT * FROM activity UNION ALL SELECT * FROM activity_ist)';
export function calendarDay(now, timeZone = 'UTC') {
  return new Intl.DateTimeFormat('en-CA', {timeZone,year:'numeric',month:'2-digit',day:'2-digit'}).format(now);
}
function reportZone(value = 'UTC') {
  if (!['UTC','Asia/Kolkata'].includes(value)) throw new Error('Unsupported reporting time zone');
  return value;
}
const activityTable = zone => reportZone(zone) === 'Asia/Kolkata' ? 'activity_ist' : 'activity';
export const downloadSql = 'INSERT OR IGNORE INTO downloads(browser,release,first_requested_at) VALUES(?,?,?)';
const currentVersionSql = `INSERT INTO installation_versions(installation,version,revision,received_at) VALUES(?,?,?,?)
 ON CONFLICT(installation) DO UPDATE SET version=excluded.version,revision=excluded.revision,received_at=excluded.received_at
 WHERE excluded.revision > installation_versions.revision`;
// Old clients report daily history only. Use their newest activity day, then
// numeric version order for same-day upgrades (0.4.13 must sort after 0.4.9).
// Explicit version revisions from newer clients also support downgrades and
// cannot be rolled back by delayed reports or retries.
const currentVersionsSql = `WITH parts AS (
 SELECT installation,day,version,substr(version,instr(version,'.')+1) AS tail FROM ${allActivity} AS a
 WHERE NOT EXISTS (SELECT 1 FROM installation_versions v WHERE v.installation=a.installation)
), ranked AS (
 SELECT installation,version,ROW_NUMBER() OVER (PARTITION BY installation ORDER BY day DESC,
 CAST(version AS INTEGER) DESC,CAST(tail AS INTEGER) DESC,
 CAST(substr(tail,instr(tail,'.')+1) AS INTEGER) DESC,
 CASE WHEN instr(version,'-')=0 THEN 1 ELSE 0 END DESC,version DESC) AS rank FROM parts
), current AS (
 SELECT version,0 AS inferred FROM installation_versions
 UNION ALL SELECT version,1 AS inferred FROM ranked WHERE rank=1
)
 SELECT version,COUNT(*) AS installations,SUM(inferred) AS inferred FROM current GROUP BY version
 ORDER BY installations DESC,version DESC`;

export function validateActivity(body, now = new Date()) {
  if (!UUID.test(body?.installationId) || !Array.isArray(body.days) || body.days.length < 1 || body.days.length > 64) throw new Error('Invalid installation or batch');
  if (body.currentVersion !== undefined || body.versionRevision !== undefined) {
    if (!VERSION.test(body.currentVersion || '') || !Number.isSafeInteger(body.versionRevision) || body.versionRevision < 1)
      throw new Error('Invalid current version');
  }
  const rows = body.days.map(row => {
    const zone = reportZone(row.timeZone);
    const today = calendarDay(now,zone), earliest = calendarDay(new Date(now.getTime()-30*DAY),zone);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(row.day) || !Number.isFinite(Date.parse(row.day)) ||
        new Date(row.day).toISOString().slice(0,10) !== row.day || row.day > today || row.day < earliest ||
        !VERSION.test(row.version) || !Number.isInteger(row.plays) || row.plays < 0 || row.plays > 10000) throw new Error('Invalid daily aggregate');
    return [body.installationId.toLowerCase(), row.day, row.version, row.plays, now.toISOString()];
  });
  return rows;
}

export async function ingest(db, body, now = new Date()) {
  const rows = validateActivity(body, now);
  const statements = rows.map((row,i) => db.prepare(body.days[i].timeZone === 'Asia/Kolkata' ? istActivitySql : activitySql).bind(...row));
  if (body.currentVersion !== undefined) statements.push(db.prepare(currentVersionSql)
    .bind(body.installationId.toLowerCase(),body.currentVersion,body.versionRevision,now.toISOString()));
  await db.batch(statements);
  return { accepted: rows.length };
}

/** First observed activity, not APK downloads or unverifiable unique people. */
export async function cohorts(db, from, to, timeZone = 'UTC') {
  const table = activityTable(timeZone);
  const [result] = await db.batch([db.prepare(`WITH first_seen AS (
    SELECT installation, MIN(day) AS first_day FROM ${allActivity} GROUP BY installation
  ), daily_install AS (
    SELECT installation, day, SUM(plays) AS plays FROM ${table}
    WHERE day BETWEEN ? AND ? GROUP BY installation, day
  ) SELECT d.day,
    SUM(CASE WHEN f.first_day=d.day THEN 1 ELSE 0 END) AS newInstallations,
    SUM(CASE WHEN f.first_day<d.day THEN 1 ELSE 0 END) AS returningInstallations,
    SUM(CASE WHEN f.first_day=d.day THEN d.plays ELSE 0 END) AS newPlays,
    SUM(CASE WHEN f.first_day<d.day THEN d.plays ELSE 0 END) AS returningPlays,
    SUM(CASE WHEN d.plays>0 THEN 1 ELSE 0 END) AS listeners
    FROM daily_install d JOIN first_seen f USING(installation)
    GROUP BY d.day ORDER BY d.day`).bind(from, to)]);
  return result.results;
}

export async function summary(db, now = new Date(), timeZone = 'UTC') {
  const table = activityTable(timeZone);
  const day = calendarDay(now,timeZone);
  const week = new Date(day+'T00:00:00Z');
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay()+6)%7));
  const month = day.slice(0,7)+'-01';
  const from = calendarDay(new Date(now.getTime()-29*DAY),timeZone);
  const results = await db.batch([
    db.prepare(`SELECT COUNT(DISTINCT installation) AS installs, COALESCE(SUM(plays),0) AS plays, MIN(day) AS since FROM ${allActivity}`),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active, COALESCE(SUM(plays),0) AS plays FROM ${table} WHERE day=?`).bind(day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active FROM ${table} WHERE day>=? AND day<=?`).bind(week.toISOString().slice(0,10),day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active FROM ${table} WHERE day>=? AND day<=?`).bind(month,day),
    db.prepare(`SELECT day,COUNT(DISTINCT installation) AS active,SUM(plays) AS plays FROM ${table} WHERE day>=? AND day<=? GROUP BY day ORDER BY day`).bind(from,day),
    db.prepare(`SELECT version,COUNT(DISTINCT installation) AS installations FROM ${allActivity} GROUP BY version ORDER BY MAX(day) DESC`),
    db.prepare('SELECT release,COUNT(*) AS browsers FROM downloads GROUP BY release ORDER BY MAX(first_requested_at) DESC'),
    db.prepare('SELECT COUNT(DISTINCT browser) AS browsers FROM downloads'),
    db.prepare(currentVersionsSql),
    db.prepare('SELECT COUNT(*) AS n FROM version_transitions'),
    db.prepare('SELECT to_version AS version, COUNT(*) AS updates FROM version_transitions GROUP BY to_version ORDER BY updates DESC, version DESC'),
  ]);
  return { asOf: now.toISOString(), timeZone, totals: results[0].results[0], today: results[1].results[0],
    week: results[2].results[0].active, month: results[3].results[0].active,
    daily: results[4].results, cohorts: await cohorts(db, from, day, timeZone), versions: results[5].results, downloads: results[6].results,
    downloadBrowsers: results[7].results[0].browsers, currentVersions: results[8].results,
    // Version moves (updates, and the occasional downgrade): counted, not mistaken for new installs.
    updates: { total: results[9].results[0].n, byVersion: results[10].results } };
}

// Inclusive calendar dates in the requested reporting zone; bounded to keep dashboard queries predictable.
export function validateRange(params, now = new Date(), timeZone = 'UTC') {
  reportZone(timeZone);
  const from = params.get('from'), to = params.get('to');
  const valid = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') &&
    Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0,10) === value;
  if (!valid(from) || !valid(to) || from > to || to > calendarDay(now,timeZone) ||
      (Date.parse(to) - Date.parse(from)) / DAY >= 366) {
    throw new Error('Choose valid reporting dates, up to 366 days, ending today or earlier');
  }
  return { from, to };
}

export async function analytics(db, range, now = new Date(), timeZone = 'UTC') {
  const { from, to } = range;
  const table = activityTable(timeZone);
  const results = await db.batch([
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active, COALESCE(SUM(plays),0) AS plays FROM ${table} WHERE day BETWEEN ? AND ?`).bind(from,to),
    db.prepare(`SELECT day, COUNT(DISTINCT installation) AS active, SUM(plays) AS plays FROM ${table} WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day`).bind(from,to),
    db.prepare(`SELECT version, COUNT(DISTINCT installation) AS installations, SUM(plays) AS plays FROM ${table} WHERE day BETWEEN ? AND ? GROUP BY version ORDER BY installations DESC, version DESC`).bind(from,to),
    db.prepare(`SELECT MIN(day) AS since FROM ${table}`),
  ]);
  return { asOf: now.toISOString(), range, timeZone, totals: results[0].results[0],
    daily: results[1].results, cohorts: await cohorts(db, from, to, timeZone), versions: results[2].results,
    coverageSince: results[3].results[0].since };
}

// Aggregate-only, no version breakdown or per-day series: safe to serve without
// a sign-in, for the README badge and a public "active this month" count.
// Counted across both tables: current apps report their days in India time
// (activity_ist), older ones in UTC (activity), and an installation is
// active if either has it. The windows follow [timeZone]'s calendar, the
// app's own by default; an old client's UTC day is at most 5.5 hours off it.
export async function publicCounts(db, now = new Date(), timeZone = 'Asia/Kolkata') {
  reportZone(timeZone);
  const table = allActivity;
  const day = calendarDay(now, timeZone);
  const week = new Date(day + 'T00:00:00Z');
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay() + 6) % 7));
  const month = day.slice(0, 7) + '-01';
  const results = await db.batch([
    db.prepare(`SELECT COUNT(DISTINCT installation) AS n FROM ${allActivity}`),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS n FROM ${table} WHERE day=?`).bind(day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS n FROM ${table} WHERE day>=? AND day<=?`).bind(week.toISOString().slice(0, 10), day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS n FROM ${table} WHERE day>=? AND day<=?`).bind(month, day),
  ]);
  return { asOf: now.toISOString(), timeZone,
    installations: results[0].results[0].n, daily: results[1].results[0].n,
    weekly: results[2].results[0].n, monthly: results[3].results[0].n };
}

// 1234 -> "1.2k", 1200000 -> "1.2M": short enough for a badge.
export function shortCount(value) {
  const n = Number(value) || 0;
  if (n < 1000) return String(n);
  if (n < 1e6) return (n / 1e3).toFixed(n < 1e4 ? 1 : 0).replace(/\.0$/, '') + 'k';
  return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
}

// A Shields.io "endpoint" badge: https://shields.io/badges/endpoint-badge
export function badge(counts, params = new URLSearchParams()) {
  const metric = params.get('metric');
  const value = metric === 'installations' ? counts.installations
    : metric === 'daily' ? counts.daily
    : metric === 'weekly' ? counts.weekly
    : counts.monthly;
  const label = params.get('label') || (metric === 'installations' ? 'installs' : 'active');
  return { schemaVersion: 1, label, message: shortCount(value), color: '1db954', cacheSeconds: 300 };
}

// Kept in this isolate's memory (one copy per Cloudflare location, gone when it
// restarts), keyed by the database binding. Public reads within [ttl] are
// answered without touching D1: the README badge alone was asking for full
// table counts every few minutes.
const remembered = new WeakMap();
export async function rememberFor(db, key, ttl, read, now = Date.now()) {
  if (!remembered.has(db)) remembered.set(db, new Map());
  const memo = remembered.get(db), hit = memo.get(key);
  if (hit && now - hit.at < ttl) return hit.value;
  const value = await read();
  memo.set(key, {at: now, value});
  return value;
}
export function forget(db, key) { remembered.get(db)?.delete(key); }

// Apps before 1.0.x send their totals after every song. The totals are
// cumulative, so a report that comes soon after the last one from the same
// installation is turned away unwritten: the app keeps it and sends it, with
// any newer plays, a little later. Each location remembers only its own.
export const REPORT_GAP_MS = 20 * 60_000;
const lastReport = new WeakMap();
export function tooSoon(db, installation, now = Date.now()) {
  if (!lastReport.has(db)) lastReport.set(db, new Map());
  const seen = lastReport.get(db), at = seen.get(installation);
  if (at !== undefined && now - at < REPORT_GAP_MS) return true;
  if (seen.size > 20000) seen.clear();
  seen.set(installation, now);
  return false;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^\/usage\/v1\/(summary|analytics|addons)$/, '/v1/$1');
    const origin = request.headers.get('Origin');
    const allowed = (env.ALLOWED_ORIGINS || 'https://justshivv.github.io').split(',');
    const headers = { ...privateHeaders, 'Content-Type':'application/json' };
    if (origin && origin !== url.origin && !allowed.includes(origin)) return new Response('{}', {status:403,headers});
    if (origin) headers['Access-Control-Allow-Origin']=origin;
    headers['Access-Control-Allow-Methods']='GET, POST, OPTIONS';
    headers['Access-Control-Allow-Headers']='Content-Type, Authorization, Accept';
    const cors = response => {
      const result = new Response(response.body,response);
      if(origin) result.headers.set('Access-Control-Allow-Origin',origin);
      return result;
    };
    const json = (body,status=200) => new Response(JSON.stringify(body),{status,headers});
    if(request.method==='OPTIONS' && ['/auth/login','/auth/logout','/v1/activity','/v1/download','/v1/summary','/v1/analytics','/v1/addons','/v1/public','/v1/badge'].includes(path)) return new Response(null,{status:204,headers});
    // Only this catalog is public. Usage analytics and catalog writes remain owner-only.
    if (request.method === 'GET' && path === '/v1/addons') {
      // A minute at most, so an add-on the owner turns off stops being asked soon after.
      try { const result = json(await rememberFor(env.DB, 'addons', 60_000, () => catalog(env.DB))); result.headers.set('Cache-Control','public, max-age=60'); return result; }
      catch { return json({error:'Catalog unavailable'},503); }
    }
    const authentication = await authRoute(request, env);
    if (authentication) return cors(authentication);
    const reporting = ['/v1/activity', '/v1/download'].includes(path) && request.method==='POST';
    // Aggregate counts for the README badge and a public total; no sign-in needed.
    const publicRead = ['/v1/public', '/v1/badge'].includes(path) && request.method==='GET';
    // Static Pages contains no private data; every read is authorized here.
    if (!reporting && !publicRead && !(await authorized(request, env))) return cors(locked(request));
    try {
      if (request.method === 'POST' && path === '/v1/addons') {
        try { const published = await publishCatalog(env.DB, await readCatalogBody(request)); forget(env.DB, 'addons'); return json(published); }
        catch (e) { return json({error:e.message},400); }
      }
      if (['GET', 'HEAD'].includes(request.method) && !path.startsWith('/v1/')) {
        if (path === '/') return new Response(null, {status:302, headers:{...privateHeaders, Location:'/usage/'}});
        if (!env.ASSETS) return json({error:'Dashboard unavailable'},503);
        const asset = await env.ASSETS.fetch(request);
        const secured = new Response(asset.body, asset);
        for (const [name,value] of Object.entries(privateHeaders)) secured.headers.set(name,value);
        return secured;
      }
      if (request.method==='GET' && path==='/v1/analytics') {
        let range,zone;
        try { zone=reportZone(url.searchParams.get('timeZone') || 'UTC'); range = validateRange(url.searchParams,new Date(),zone); } catch(e) { return json({error:e.message},400); }
        return json(await analytics(env.DB,range,new Date(),zone));
      }
      if (request.method==='GET' && path==='/v1/summary') {
        let zone;try { zone=reportZone(url.searchParams.get('timeZone') || 'UTC'); } catch(e) {return json({error:e.message},400);}
        return json(await summary(env.DB,new Date(),zone));
      }
      if (request.method==='GET' && path==='/v1/public') {
        let zone;try { zone=reportZone(url.searchParams.get('timeZone') || 'Asia/Kolkata'); } catch(e) {return json({error:e.message},400);}
        return json(await rememberFor(env.DB, 'public:'+zone, 300_000, () => publicCounts(env.DB,new Date(),zone)));
      }
      if (request.method==='GET' && path==='/v1/badge') {
        return json(badge(await rememberFor(env.DB, 'public:Asia/Kolkata', 300_000, () => publicCounts(env.DB,new Date())),url.searchParams));
      }
      if (request.method!=='POST' || !['/v1/activity','/v1/download'].includes(path)) return json({error:'Not found'},404);
      if (!request.headers.get('Content-Type')?.startsWith('application/json')) return json({error:'JSON required'},415);
      // Bounded streaming read: don't trust Content-Length from public clients.
      const reader = request.body?.getReader();
      if (!reader) return json({error:'Body required'},400);
      let size=0, chunks=[];
      while (true) { const {done,value}=await reader.read(); if(done) break; size+=value.length;
        if(size>16384) { await reader.cancel(); return json({error:'Too large'},413); } chunks.push(value); }
      const bytes = new Uint8Array(size); let offset=0;
      for(const chunk of chunks) { bytes.set(chunk,offset); offset+=chunk.length; }
      let body;
      try { body=JSON.parse(new TextDecoder().decode(bytes)); } catch { return json({error:'Invalid JSON'},400); }
      if (path==='/v1/activity') {
        try { validateActivity(body); } catch(e) { return json({error:e.message},400); }
        if (tooSoon(env.DB, body.installationId.toLowerCase())) {
          const later = json({error:'Reported moments ago; send the totals later'},429);
          later.headers.set('Retry-After', String(REPORT_GAP_MS / 1000));
          return later;
        }
        return json(await ingest(env.DB,body));
      }
      if(!UUID.test(body?.browserId) || !VERSION.test(body?.release)) return json({error:'Invalid browser or release'},400);
      await env.DB.prepare(downloadSql).bind(body.browserId.toLowerCase(),body.release,new Date().toISOString()).run();
      return json({accepted:true});
    } catch { return json({error:'Temporarily unavailable; retry the same payload'},503); }
  }
};

