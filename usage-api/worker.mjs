import { catalog, publishCatalog, readCatalogBody } from './addons.mjs';
import { authorized, locked, privateHeaders, authRoute } from './auth.mjs';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const VERSION = /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]{1,32})?$/;
const DAY = 86400000;
// Older apps report today's plays so far after every song, and each saved change costs a row
// written (daily_totals is recounted nightly, not by a trigger): D1's free plan allows 100,000 a
// day. A day that's still going is saved at most once every SAVE_TODAY_EVERY_MS; days that are
// over always are, and current apps only send those. The last binds are the reporting zone's today and that cutoff.
export const SAVE_TODAY_EVERY_MS = 2 * 3600000;
export const activitySql = `INSERT INTO activity(installation,day,version,plays,received_at) VALUES(?,?,?,?,?)
 ON CONFLICT(installation,day,version) DO UPDATE SET plays=excluded.plays,received_at=excluded.received_at
 WHERE excluded.plays > activity.plays AND (excluded.day < ? OR activity.received_at <= ?)`;
const istActivitySql = activitySql.replaceAll('activity', 'activity_ist');
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
// Only each legacy installation's newest day can rank first, so only that day's rows are read
// (found through the primary key), never the whole history.
export const currentVersionsSql = `WITH legacy AS MATERIALIZED (
 SELECT i.installation,MAX(COALESCE((SELECT MAX(day) FROM activity a WHERE a.installation=i.installation),''),
 COALESCE((SELECT MAX(day) FROM activity_ist a WHERE a.installation=i.installation),'')) AS day
 FROM installations i WHERE NOT EXISTS (SELECT 1 FROM installation_versions v WHERE v.installation=i.installation)
), parts AS (
 SELECT a.installation,a.day,a.version,substr(a.version,instr(a.version,'.')+1) AS tail FROM legacy l
 CROSS JOIN activity a ON a.installation=l.installation AND a.day=l.day
 UNION ALL SELECT a.installation,a.day,a.version,substr(a.version,instr(a.version,'.')+1) AS tail FROM legacy l
 CROSS JOIN activity_ist a ON a.installation=l.installation AND a.day=l.day
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
    return [body.installationId.toLowerCase(), row.day, row.version, row.plays, now.toISOString(),
      today, new Date(now.getTime() - SAVE_TODAY_EVERY_MS).toISOString()];
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

// ---------------------------------------------------------------------------
// The owner's dashboard. Counted straight from the activity rows, opening it (/v1/summary and
// /v1/analytics for the last 30 days) read about a million rows, and D1's free plan allows 5
// million a day. So nothing on that path reads the activity history any more:
//  * Each night the cron counts the last WINDOW_DAYS days of each reporting zone into one saved
//    row: unique installations, new ones and their plays, and versions since each day a preset
//    range, this week or this month can start on, and each day's listeners and new
//    installations. A range starting on one of those days reads no activity rows up to the count.
//  * Reports since the count (today's, mostly) are read live: one row per installation and
//    version active since, plus that installation's first day and its last day active before.
//  * Days before the window can no longer change (apps send at most 30 days back); each one's
//    new/returning row is kept for good in daily_cohorts.
//  * Lifetime figures are counted nightly as well. The tables that only grow (versions seen,
//    downloads, updates) are then read past that count by rowid, so those stay exact; current
//    versions are as of the nightly count (currentVersionsAsOf).
//  * The Worker keeps whole responses for REPORT_TTL_MS (report_cache and memory), so opening the
//    dashboard again reads one row.
// Every copy kept is a write that D1 may refuse (the free plan's daily limit): the answer is still
// returned, and kept in this instance's memory. Before schema.sql has created the tables, the
// same figures are counted from the activity rows, and kept in memory.
export const REPORT_TTL_MS = 30 * 60_000;
export const WINDOW_DAYS = 31;
const KEPT = 'dash2:';   // changed whenever a kept copy changes shape, so a deploy never reads the old one
const addDays = (day, n) => new Date(Date.parse(day + 'T00:00:00Z') + n * DAY).toISOString().slice(0, 10);
const mondayOf = day => addDays(day, -((new Date(day + 'T00:00:00Z').getUTCDay() + 6) % 7));
const monthOf = day => day.slice(0, 7) + '-01';
const before = (counts, day) => Object.entries(counts).reduce((n, [d, v]) => d < day ? n + v : n, 0);
const onOrAfter = (counts, day) => Object.entries(counts).reduce((n, [d, v]) => d >= day ? n + v : n, 0);
const tally = (counts, key, n = 1) => { counts[key] = (counts[key] || 0) + n; };
const descending = (a, b) => a < b ? 1 : a > b ? -1 : 0;

const memo = new WeakMap(), working = new WeakMap();
const mapOf = (store, db) => store.get(db) || store.set(db, new Map()).get(db);
async function keptCopy(db, key) {
  try {
    const [{results: [row]}] = await db.batch([db.prepare('SELECT payload,computed_at FROM report_cache WHERE key=?1').bind(KEPT + key)]);
    return row ? {at: Number(row.computed_at), value: JSON.parse(row.payload)} : null;
  } catch { return null; }   // no table yet
}
async function keepCopy(db, key, value, at) {
  try {
    await db.prepare(`INSERT INTO report_cache(key,payload,computed_at) VALUES(?1,?2,?3)
      ON CONFLICT(key) DO UPDATE SET payload=excluded.payload,computed_at=excluded.computed_at`).bind(KEPT + key, JSON.stringify(value), at).run();
  } catch { /* refused, or no table yet: kept in memory by the caller */ }
}
function hold(db, key, entry, now) {
  const mem = mapOf(memo, db);
  if (mem.size > 100) mem.clear();
  mem.set(key, {...entry, held: now});
}
/**
 * The value kept under [key] while [fresh] says so: from this instance's memory (for at most
 * [memoryMs]), else from report_cache, else [compute]d and kept in both. Concurrent callers share
 * one computation. With [shared] false only memory is used.
 */
export async function reuse(db, key, isFresh, compute, {now = Date.now(), shared = true, memoryMs = Infinity} = {}) {
  const fresh = entry => entry.at <= now + 60_000 && isFresh(entry);   // never one dated ahead of the clock
  const hit = mapOf(memo, db).get(key);
  if (hit && fresh(hit) && now - hit.held < memoryMs) return hit.value;
  const busy = mapOf(working, db);
  if (!busy.has(key)) busy.set(key, (async () => {
    const copy = shared ? await keptCopy(db, key) : null;
    const entry = copy && fresh(copy) ? copy : {at: now, value: await compute()};
    if (shared && entry !== copy) await keepCopy(db, key, entry.value, now);
    hold(db, key, entry, now);
    return entry.value;
  })().finally(() => busy.delete(key)));
  return busy.get(key);
}

// The first days a range can start on and still be answered from the nightly count: today, the
// 7- and 30-day presets, this week and this month, for the day after the count and the day after
// that (the count runs at 06:05 India time, so from midnight until then it is a day older).
function windowStarts(through) {
  const starts = new Set();
  for (const today of [addDays(through, 1), addDays(through, 2)])
    for (const from of [addDays(today, -6), addDays(today, -29), mondayOf(today), monthOf(today)]) starts.add(from);
  return [...starts].filter(from => from <= through).sort();
}
// Installations seen for the first time in [?1, ?2], with their plays over the same days (so,
// all of them), and how many of those were active, and their plays, on that first day itself.
const newSql = table => `SELECT i.first_day AS day,COUNT(DISTINCT i.installation) AS installs,COALESCE(SUM(a.plays),0) AS plays,
 COUNT(DISTINCT CASE WHEN a.day=i.first_day THEN i.installation END) AS firstDay,
 COALESCE(SUM(CASE WHEN a.day=i.first_day THEN a.plays END),0) AS firstDayPlays
 FROM installations i JOIN ${table} a ON a.installation=i.installation AND a.day BETWEEN i.first_day AND ?2
 WHERE i.first_day BETWEEN ?1 AND ?2 GROUP BY i.first_day`;

/** The nightly count of [zone]'s last WINDOW_DAYS days, up to and including [through]. */
export async function countWindow(db, zone, through) {
  const table = activityTable(zone), start = addDays(through, 1 - WINDOW_DAYS), starts = windowStarts(through);
  const days = [];
  for (let day = start; day <= through; day = addDays(day, 1)) days.push(day);
  // Each COUNT(DISTINCT) is worked out in the one pass over the window's rows.
  const since = starts.map((_, i) => `COUNT(DISTINCT CASE WHEN day>=?${i + 3} THEN installation END) AS s${i}`);
  const heard = days.map((_, i) => `COUNT(DISTINCT CASE WHEN day=?${starts.length + i + 3} AND plays>0 THEN installation END) AS h${i}`);
  // Versions are needed for the 7- and 30-day presets only (the summary's week and month show none).
  const presets = [...new Set([-5, -4, -28, -27].map(n => addDays(through, n)))].sort();
  const [{results: seen}] = await db.batch([db.prepare('SELECT version FROM versions_seen WHERE last_day>=?1').bind(start)]);
  // Two columns per version and preset, in one pass, while they fit (D1 allows 100); else a sort.
  const listed = seen.length > 0 && seen.length * presets.length * 2 <= 88;
  const at = i => `?${i + 3}`, named = j => `?${presets.length + j + 3}`;
  const byVersion = listed
    ? seen.flatMap((_, j) => presets.map((_, i) => `COUNT(DISTINCT CASE WHEN version=${named(j)} AND day>=${at(i)} THEN installation END) AS i${j}_${i},` +
      `COALESCE(SUM(CASE WHEN version=${named(j)} AND day>=${at(i)} THEN plays END),0) AS p${j}_${i}`))
    : presets.map((_, i) => `COUNT(DISTINCT CASE WHEN day>=${at(i)} THEN installation END) AS i0_${i},SUM(CASE WHEN day>=${at(i)} THEN plays ELSE 0 END) AS p0_${i}`);
  const [[unique = {}], versionRows, firsts] = (await db.batch([
    db.prepare(`SELECT ${[...since, ...heard].join(',')} FROM ${table} WHERE day BETWEEN ?1 AND ?2`).bind(start, through, ...starts, ...days),
    listed
      ? db.prepare(`SELECT ${byVersion.join(',')} FROM ${table} WHERE day BETWEEN ?1 AND ?2`).bind(start, through, ...presets, ...seen.map(v => v.version))
      : db.prepare(`SELECT version,${byVersion.join(',')} FROM ${table} WHERE day BETWEEN ?1 AND ?2 GROUP BY version`).bind(start, through, ...presets),
    db.prepare(newSql(table)).bind(start, through),
  ])).map(result => result.results);
  const versions = listed
    ? seen.map(({version}, j) => ({ version, installs: presets.map((_, i) => versionRows[0][`i${j}_${i}`]), plays: presets.map((_, i) => versionRows[0][`p${j}_${i}`]) }))
    : versionRows.map(row => ({ version: row.version, installs: presets.map((_, i) => row[`i0_${i}`]), plays: presets.map((_, i) => row[`p0_${i}`]) }));
  return { zone, start, through, starts, presets,
    active: starts.map((_, i) => unique[`s${i}`] ?? 0),
    listeners: Object.fromEntries(days.map((day, i) => [day, unique[`h${i}`] ?? 0])),
    versions: versions.filter(v => v.installs.some(n => n > 0)),
    firsts: Object.fromEntries(firsts.map(row => [row.day, [row.installs, row.plays, row.firstDay, row.firstDayPlays]])) };
}
const nothing = { active: 0, new: 0, newPlays: 0, versions: [] };
function fromWindow(window, from) {
  const i = window.starts.indexOf(from), j = window.presets.indexOf(from);
  let installs = 0, plays = 0;
  for (const [day, [n, p]] of Object.entries(window.firsts)) if (day >= from) { installs += n; plays += p; }
  return { active: window.active[i], new: installs, newPlays: plays, versions: j < 0 ? [] :
    window.versions.filter(v => v.installs[j] > 0).map(v => ({ version: v.version, installations: v.installs[j], plays: v.plays[j] })) };
}
function windowDay(window, day, live) {
  const [, , n = 0, p = 0] = window.firsts[day] || [];
  return { day, newInstallations: n, returningInstallations: Math.max(0, live.active - n), newPlays: p,
    returningPlays: Math.max(0, live.plays - p), listeners: window.listeners[day] ?? 0 };
}
// The kept count for [zone], made last night (or, failing that, now). From midnight until the
// nightly run it is a day old, and the day between is read live with today.
function windowCount(db, zone, now) {
  const today = calendarDay(new Date(now), zone), oldest = addDays(today, -2);
  return reuse(db, 'window:' + zone, entry => entry.value.through >= oldest && entry.value.through < today,
    () => countWindow(db, zone, addDays(today, -1)), { now, memoryMs: 60 * 60_000 });
}

/** Unique installations, new ones and their plays, and versions, over [from, through], counted directly. */
async function countRange(db, zone, from, through) {
  const table = activityTable(zone);
  // Every version seen on or after [from] (versions_seen, a few rows) gets two columns, so the
  // unique count and the versions come from one pass over the rows instead of a pass and a sort.
  const [{results: seen}] = await db.batch([db.prepare('SELECT version FROM versions_seen WHERE last_day>=?1').bind(from)]);
  if (seen.length <= 40) {
    const columns = seen.map((_, i) => `COUNT(DISTINCT CASE WHEN version=?${i + 3} THEN installation END) AS i${i},COALESCE(SUM(CASE WHEN version=?${i + 3} THEN plays END),0) AS p${i}`);
    const [[unique], [added]] = (await db.batch([
      db.prepare(`SELECT ${['COUNT(DISTINCT installation) AS active', ...columns].join(',')} FROM ${table} WHERE day BETWEEN ?1 AND ?2`).bind(from, through, ...seen.map(v => v.version)),
      db.prepare(`SELECT COALESCE(SUM(installs),0) AS installs,COALESCE(SUM(plays),0) AS plays FROM (${newSql(table)})`).bind(from, through),
    ])).map(result => result.results);
    return { active: unique.active, new: added.installs, newPlays: added.plays,
      versions: seen.map(({version}, i) => ({ version, installations: unique[`i${i}`], plays: unique[`p${i}`] })).filter(v => v.installations > 0) };
  }
  const [[unique], versions, [added]] = (await db.batch([
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active FROM ${table} WHERE day BETWEEN ?1 AND ?2`).bind(from, through),
    db.prepare(`SELECT version,COUNT(DISTINCT installation) AS installations,SUM(plays) AS plays FROM ${table} WHERE day BETWEEN ?1 AND ?2 GROUP BY version`).bind(from, through),
    db.prepare(`SELECT COALESCE(SUM(installs),0) AS installs,COALESCE(SUM(plays),0) AS plays FROM (${newSql(table)})`).bind(from, through),
  ])).map(result => result.results);
  return { active: unique.active, new: added.installs, newPlays: added.plays, versions };
}

/**
 * Activity from [r0] to [to], with each installation's first day and its last active day in
 * [lo, r0): what a count up to the day before r0 needs to cover a range [from, to], lo <= from <= r0.
 * One row per installation and version active since; nothing per installation is kept.
 */
async function countRecent(db, zone, r0, to, lo) {
  const table = activityTable(zone);
  const [{results}] = await db.batch([db.prepare(`SELECT a.installation,a.day,a.version,a.plays,
    (SELECT i.first_day FROM installations i WHERE i.installation=a.installation) AS first,
    (SELECT p.day FROM ${table} p WHERE p.installation=a.installation AND p.day>=?3 AND p.day<?1 ORDER BY p.day DESC LIMIT 1) AS prev,
    (SELECT p.day FROM ${table} p WHERE p.installation=a.installation AND p.version=a.version AND p.day>=?3 AND p.day<?1 ORDER BY p.day DESC LIMIT 1) AS prevVersion
    FROM ${table} a WHERE a.day BETWEEN ?1 AND ?2`).bind(r0, to, lo)]);
  // Keyed by day: '' is "not active in [lo, r0)".
  const out = { r0, to, lo, lastBefore: {}, firstSeen: {}, firstPlays: {}, versions: {}, days: {} };
  const installs = new Set(), pairs = new Set(), byDay = {};
  for (const row of results) {
    if (!installs.has(row.installation)) {
      installs.add(row.installation);
      tally(out.lastBefore, row.prev || '');
      if (!row.prev && row.first >= lo) tally(out.firstSeen, row.first);
    }
    if (row.first >= lo) tally(out.firstPlays, row.first, row.plays);
    const version = out.versions[row.version] ??= { plays: 0, lastBefore: {} };
    version.plays += row.plays;
    if (!pairs.has(row.installation + ' ' + row.version)) {
      pairs.add(row.installation + ' ' + row.version);
      tally(version.lastBefore, row.prevVersion || '');
    }
    const day = byDay[row.day] ??= new Map(), seen = day.get(row.installation) || { first: row.first, plays: 0 };
    seen.plays += row.plays; day.set(row.installation, seen);
  }
  for (const [day, seen] of Object.entries(byDay)) {
    const row = { day, newInstallations: 0, returningInstallations: 0, newPlays: 0, returningPlays: 0, listeners: 0 };
    for (const { first, plays } of seen.values()) {
      if (first == null) continue;   // as cohorts() did: counted with its first day only
      if (first === day) { row.newInstallations++; row.newPlays += plays; }
      else { row.returningInstallations++; row.returningPlays += plays; }
      if (plays > 0) row.listeners++;
    }
    out.days[day] = row;
  }
  return out;
}
function withRecent(base, recent, from) {
  if (!recent) return { ...base, versions: base.versions.map(v => ({ ...v })) };
  const versions = new Map(base.versions.map(v => [v.version, { ...v }]));
  for (const [version, v] of Object.entries(recent.versions)) {
    const row = versions.get(version) || { version, installations: 0, plays: 0 };
    row.installations += before(v.lastBefore, from); row.plays += v.plays;
    versions.set(version, row);
  }
  return { active: base.active + before(recent.lastBefore, from), new: base.new + onOrAfter(recent.firstSeen, from),
    newPlays: base.newPlays + onOrAfter(recent.firstPlays, from), versions: [...versions.values()].filter(v => v.installations > 0) };
}

/**
 * Unique installations, new ones and their plays, and versions in [zone] over [from, to]: the
 * nightly count when the range starts on one of its days, else a direct count up to yesterday
 * (kept until the next night), plus the days since read live.
 */
async function rangeCount(c, from, to, { versions = true } = {}) {
  const { db, zone, window, today, now } = c;
  let base, r0, lo;
  const counted = window.starts.includes(from) && (!versions || window.presets.includes(from));
  if (to >= window.through && (from > window.through || counted)) {
    base = from > window.through ? nothing : fromWindow(window, from);
    r0 = from > window.through ? from : addDays(window.through, 1); lo = window.start;
  } else {
    const through = to < today ? to : addDays(today, -1), final = through < addDays(today, -WINDOW_DAYS);
    base = from > through ? nothing : await reuse(db, `range:${zone}:${from}:${through}`,
      entry => now - entry.at < (final ? 7 * DAY : 12 * 60 * 60_000), () => countRange(db, zone, from, through), { now });
    r0 = from > through ? from : addDays(through, 1); lo = from;
  }
  // The live part is shared by every range and page that needs the same days (and, through
  // report_cache, by the summary and analytics requests of one dashboard load).
  const key = `recent:${zone}:${r0}:${to}:${lo}`, count = async () => ({ ...await countRecent(db, zone, r0, to, lo), at: now });
  if (r0 <= to && !c.recents.has(key))
    c.recents.set(key, c.shared ? reuse(db, key, entry => now - entry.at < REPORT_TTL_MS, count, { now }) : count());
  const recent = r0 <= to ? await c.recents.get(key) : null;
  return { ...withRecent(base, recent, from), recent };
}
async function dashboardContext(db, zone, now, shared) {
  const at = now.getTime();
  return { db, zone, now: at, today: calendarDay(now, zone), shared, recents: new Map(), window: await windowCount(db, zone, at) };
}

const cohortColumns = 'day,new_installations AS newInstallations,returning_installations AS returningInstallations,new_plays AS newPlays,returning_plays AS returningPlays,listeners';
async function keepCohorts(db, zone, rows) {
  if (!rows.length) return;
  try {
    await db.batch(rows.map(r => db.prepare(`INSERT OR IGNORE INTO daily_cohorts(zone,day,new_installations,returning_installations,new_plays,returning_plays,listeners)
      VALUES(?1,?2,?3,?4,?5,?6,?7)`).bind(zone, r.day, r.newInstallations, r.returningInstallations, r.newPlays, r.returningPlays, r.listeners)));
  } catch { /* refused, or no table yet: counted again next time */ }
}
/** Each day's new and returning installations and listeners, for the [daily] days with activity. */
async function cohortRows({ db, zone, window, today }, daily, recent) {
  if (!daily.length) return [];
  const rows = new Map(), missing = [];
  let kept = [];
  const oldest = daily[0].day;
  if (oldest < window.start) {
    try {
      [{results: kept}] = await db.batch([db.prepare(`SELECT ${cohortColumns} FROM daily_cohorts WHERE zone=?1 AND day BETWEEN ?2 AND ?3`)
        .bind(zone, oldest, addDays(window.start, -1))]);
    } catch { /* no table yet */ }
  }
  const stored = new Map(kept.map(row => [row.day, row]));
  for (const live of daily) {
    const { day } = live;
    if (recent && day >= recent.r0) rows.set(day, recent.days[day]);
    else if (day >= window.start && day <= window.through) rows.set(day, windowDay(window, day, live));
    else if (stored.has(day)) rows.set(day, { ...stored.get(day) });
    else missing.push(live);
  }
  if (missing.length) {
    // Days not counted yet: a day before the window is final, so it is kept for good.
    const table = activityTable(zone), start = missing[0].day, end = missing.at(-1).day;
    const [heard, firsts] = (await db.batch([
      db.prepare(`SELECT day,COUNT(DISTINCT CASE WHEN plays>0 THEN installation END) AS listeners FROM ${table} WHERE day BETWEEN ?1 AND ?2 GROUP BY day`).bind(start, end),
      db.prepare(`SELECT i.first_day AS day,COUNT(DISTINCT i.installation) AS installs,COALESCE(SUM(a.plays),0) AS plays FROM installations i
        JOIN ${table} a ON a.installation=i.installation AND a.day=i.first_day WHERE i.first_day BETWEEN ?1 AND ?2 GROUP BY i.first_day`).bind(start, end),
    ])).map(result => result.results);
    const listeners = new Map(heard.map(row => [row.day, row.listeners])), added = new Map(firsts.map(row => [row.day, row]));
    const counted = missing.map(live => {
      const n = added.get(live.day)?.installs ?? 0, p = added.get(live.day)?.plays ?? 0;
      return { day: live.day, newInstallations: n, returningInstallations: Math.max(0, live.active - n), newPlays: p,
        returningPlays: Math.max(0, live.plays - p), listeners: listeners.get(live.day) ?? 0 };
    });
    for (const row of counted) rows.set(row.day, row);
    await keepCohorts(db, zone, counted.filter(row => row.day < addDays(today, -WINDOW_DAYS)));
  }
  return daily.map(live => rows.get(live.day)).filter(Boolean);
}

// Each day's active installations and plays per zone (daily_totals, see schema.sql). No trigger keeps
// it: that cost a row written on every report. The cron recounts each zone's last RECOUNT_DAYS days
// and today, and the dashboard reads the days since that recount may have missed (yesterday, as a
// report for it can still come in after the count, and today) straight from the activity rows.
// A late report for an older day (apps send up to 30 days back) shows once the next nightly
// recount has run; until then that day's row in daily_totals is short of it. Reports for a day
// are accepted until it is 30 days old, so the recount on the night it is 31 days old sees it final.
export const RECOUNT_DAYS = 31;
/**
 * Recounts each zone's days from today-RECOUNT_DAYS to today: one grouped pass over the day index,
 * writing only the days whose figures changed. Counted as the triggers did: an installation that
 * reported a day under two versions (an update mid-day) is one installation, with both rows' plays.
 */
export async function recountDailyTotals(db, now = new Date()) {
  let written = 0;
  for (const zone of ['Asia/Kolkata', 'UTC']) {
    const today = calendarDay(now, zone);
    const done = await db.prepare(`INSERT INTO daily_totals(zone,day,installations,plays)
      SELECT ?1,day,COUNT(DISTINCT installation),SUM(plays) FROM ${activityTable(zone)} WHERE day BETWEEN ?2 AND ?3 GROUP BY day
      ON CONFLICT(zone,day) DO UPDATE SET installations=excluded.installations,plays=excluded.plays
      WHERE daily_totals.installations<>excluded.installations OR daily_totals.plays<>excluded.plays`)
      .bind(zone, addDays(today, -RECOUNT_DAYS), today).run();
    written += done?.meta?.changes ?? done?.changes ?? 0;
  }
  return written;
}
// The live days of [zone] (yesterday on, so also a day ahead of this clock), as daily_totals rows.
const liveSql = table => `SELECT day,COUNT(DISTINCT installation) AS active,SUM(plays) AS plays FROM ${table} WHERE day>=?1 GROUP BY day ORDER BY day`;
function liveDays(db, zone, now, shared) {
  const from = addDays(calendarDay(new Date(now), zone), -1);
  const count = async () => ({ from, rows: (await db.batch([db.prepare(liveSql(activityTable(zone))).bind(from)]))[0].results });
  // A dashboard load's summary and analytics share the read when they meet in one instance; never
  // written to report_cache, which would cost a row written for a few thousand rows read.
  return shared ? reuse(db, `days:${zone}:${from}`, entry => now - entry.at < REPORT_TTL_MS, count, { now, shared: false }) : count();
}
/** daily_totals [rows] before the live days, then the live days in [from, to]. */
const withLiveDays = (rows, live, from, to) =>
  [...rows.filter(row => row.day < live.from), ...live.rows.filter(row => row.day >= from && row.day <= to)];

// Lifetime figures, counted nightly. Rows only ever get added to version_installs, downloads and
// version_transitions, so each count goes on from the last one by rowid (from scratch once a week).
function growth(db, ids) {
  return [
    db.prepare('SELECT version,COUNT(*) AS n,MAX(rowid) AS id FROM version_installs NOT INDEXED WHERE rowid>?1 GROUP BY version').bind(ids.installs),
    db.prepare('SELECT release,COUNT(*) AS n,MAX(first_requested_at) AS last,MAX(rowid) AS id FROM downloads NOT INDEXED WHERE rowid>?1 GROUP BY release').bind(ids.downloads),
    db.prepare('SELECT COUNT(DISTINCT browser) AS n FROM downloads d NOT INDEXED WHERE rowid>?1 AND (?1=0 OR NOT EXISTS (SELECT 1 FROM downloads o WHERE o.browser=d.browser AND o.rowid<=?1))').bind(ids.downloads),
    db.prepare('SELECT to_version AS version,COUNT(*) AS n,MAX(rowid) AS id FROM version_transitions NOT INDEXED WHERE rowid>?1 GROUP BY to_version').bind(ids.moves),
  ];
}
function grown(life, [installs, releases, [browsers], moves]) {
  const ids = { ...life.ids }, versions = { ...life.versions }, downloads = {}, updates = { ...life.updates };
  for (const [release, d] of Object.entries(life.downloads)) downloads[release] = { ...d };
  for (const r of installs) { tally(versions, r.version, r.n); ids.installs = Math.max(ids.installs, r.id); }
  for (const r of releases) {
    const d = downloads[r.release] ??= { browsers: 0, last: '' };
    d.browsers += r.n; if (r.last > d.last) d.last = r.last;
    ids.downloads = Math.max(ids.downloads, r.id);
  }
  for (const r of moves) { tally(updates, r.version, r.n); ids.moves = Math.max(ids.moves, r.id); }
  return { ...life, ids, versions, downloads, browsers: life.browsers + (browsers?.n ?? 0), updates };
}
export async function countLifetime(db, now = Date.now(), previous = null) {
  const fresh = !previous || now - previous.full >= 7 * DAY;
  const last = fresh ? { full: now, ids: { installs: 0, downloads: 0, moves: 0 }, versions: {}, downloads: {}, browsers: 0, updates: {}, cutoff: '', plays: 0 } : previous;
  // Days at least 32 days old can't change any more: their plays are added up once. Each was last
  // recounted the night it was 31 days old (RECOUNT_DAYS), with every report it can get.
  const cutoff = calendarDay(new Date(now - 32 * DAY), 'UTC');
  const [current, ...rest] = (await db.batch([
    db.prepare(currentVersionsSql),
    ...growth(db, last.ids),
    db.prepare("SELECT COALESCE(SUM(plays),0) AS plays FROM daily_totals WHERE zone IN ('UTC','Asia/Kolkata') AND day>=?1 AND day<?2").bind(last.cutoff, cutoff),
  ])).map(result => result.results);
  const plays = rest.pop()[0].plays;
  return { ...grown(last, rest), currentVersions: current, cutoff, plays: last.plays + plays, at: now };
}
function lifetime(db, now) {
  return reuse(db, 'lifetime', entry => now - entry.at < 26 * 60 * 60_000, () => countLifetime(db, now), { now, memoryMs: 60 * 60_000 });
}

export async function summary(db, now = new Date(), timeZone = 'UTC', { shared = false } = {}) {
  const zone = reportZone(timeZone), at = now.getTime();
  const day = calendarDay(now, zone), from = addDays(day, -29);
  const [life, c, ist, utc] = await Promise.all([lifetime(db, at), dashboardContext(db, zone, now, shared),
    liveDays(db, 'Asia/Kolkata', at, shared), liveDays(db, 'UTC', at, shared)]);
  const live = zone === 'UTC' ? utc : ist;
  const [[{installs}], [{plays}], [{since}], counted, seen, ...added] = (await db.batch([
    // Lifetime installations come from the count a trigger keeps; lifetime plays are the nightly
    // sum of the days that can't change any more, the last few days' rows of daily_totals up to
    // each zone's live days, and those days themselves (added below).
    db.prepare('SELECT installations AS installs FROM totals WHERE id=1'),
    db.prepare(`SELECT (SELECT COALESCE(SUM(plays),0) FROM daily_totals WHERE zone='UTC' AND day>=?1 AND day<?2)
      + (SELECT COALESCE(SUM(plays),0) FROM daily_totals WHERE zone='Asia/Kolkata' AND day>=?1 AND day<?3) AS plays`).bind(life.cutoff, utc.from, ist.from),
    // The first day of each table, through its day index (one row each): a day not recounted yet counts too.
    db.prepare(`SELECT MIN(day) AS since FROM (SELECT MIN(day) AS day FROM daily_totals WHERE zone='UTC' UNION ALL SELECT MIN(day) FROM daily_totals WHERE zone='Asia/Kolkata'
      UNION ALL SELECT MIN(day) FROM activity UNION ALL SELECT MIN(day) FROM activity_ist)`),
    // A day's figures are one row of daily_totals (see schema.sql), not every report that day.
    db.prepare('SELECT day,installations AS active,plays FROM daily_totals WHERE zone=?1 AND day>=?2 AND day<=?3 AND day<?4 ORDER BY day').bind(zone, from, day, live.from),
    db.prepare('SELECT version FROM versions_seen ORDER BY last_day DESC'),
    ...growth(db, life.ids),
  ])).map(result => result.results);
  const sofar = grown(life, added);
  const daily = withLiveDays(counted, live, from, day);
  const livePlays = [...ist.rows, ...utc.rows].reduce((n, row) => n + row.plays, 0);
  const today = daily.at(-1)?.day === day ? daily.at(-1) : { active: 0, plays: 0 };
  const weekly = await rangeCount(c, mondayOf(day), day, { versions: false }), monthly = await rangeCount(c, monthOf(day), day, { versions: false });
  const recent = [weekly.recent, monthly.recent].filter(Boolean).sort((a, b) => a.r0 < b.r0 ? -1 : 1)[0];
  return { asOf: new Date(recent?.at ?? at).toISOString(), timeZone,
    totals: { installs, plays: life.plays + plays + livePlays, since }, today: { active: today.active, plays: today.plays },
    week: weekly.active, month: monthly.active,
    daily, cohorts: await cohortRows(c, daily, recent),
    versions: seen.map(({version}) => ({ version, installations: sofar.versions[version] || 0 })),
    downloads: Object.entries(sofar.downloads).sort((a, b) => descending(a[1].last, b[1].last)).map(([release, d]) => ({ release, browsers: d.browsers })),
    downloadBrowsers: sofar.browsers,
    // Each installation under its latest version, as of the nightly count.
    currentVersions: life.currentVersions, currentVersionsAsOf: new Date(life.at).toISOString(),
    // Version moves (updates, and the occasional downgrade): counted, not mistaken for new installs.
    updates: { total: Object.values(sofar.updates).reduce((n, v) => n + v, 0),
      byVersion: Object.entries(sofar.updates).map(([version, updates]) => ({ version, updates }))
        .sort((a, b) => b.updates - a.updates || descending(a.version, b.version)) } };
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

export async function analytics(db, range, now = new Date(), timeZone = 'UTC', { shared = false } = {}) {
  const { from, to } = range, zone = reportZone(timeZone);
  const liveFrom = addDays(calendarDay(now, zone), -1);
  const [c, live] = await Promise.all([dashboardContext(db, zone, now, shared),
    to >= liveFrom ? liveDays(db, zone, now.getTime(), shared) : { from: liveFrom, rows: [] }]);
  const [stored, [{since}]] = (await db.batch([
    // daily_totals up to the live days (yesterday and today), which are read from the activity rows.
    db.prepare('SELECT day,installations AS active,plays FROM daily_totals WHERE zone=?1 AND day BETWEEN ?2 AND ?3 AND day<?4 ORDER BY day').bind(zone, from, to, live.from),
    db.prepare(`SELECT MIN(day) AS since FROM (SELECT MIN(day) AS day FROM daily_totals WHERE zone=?1 UNION ALL SELECT MIN(day) FROM ${activityTable(zone)})`).bind(zone),
  ])).map(result => result.results);
  const daily = withLiveDays(stored, live, from, to);
  const counted = await rangeCount(c, from, to);
  const plays = daily.reduce((n, row) => n + row.plays, 0);
  return { asOf: new Date(counted.recent?.at ?? c.now).toISOString(), range, timeZone, totals: { active: counted.active, plays },
    // The period as a whole, split by when each installation was first seen: new ones were first
    // seen within [from, to], returning ones before it. Together they make the period's unique
    // active installations, each counted once.
    split: { newInstallations: counted.new, returningInstallations: counted.active - counted.new,
      newPlays: counted.newPlays, returningPlays: plays - counted.newPlays },
    daily, cohorts: await cohortRows(c, daily, counted.recent),
    versions: counted.versions.sort((a, b) => b.installations - a.installations || descending(a.version, b.version)),
    coverageSince: since };
}

/** Last night's counts for the dashboard, made by the cron so that no request has to. */
export async function refreshDashboard(db, now = new Date()) {
  const at = now.getTime();
  for (const zone of ['Asia/Kolkata', 'UTC']) {
    const window = await countWindow(db, zone, addDays(calendarDay(now, zone), -1));
    await keepCopy(db, 'window:' + zone, window, at);
    hold(db, 'window:' + zone, { at, value: window }, at);
    // Reports for the window's first day are no longer accepted: its row is final. Counted from its
    // activity rows (one day, through the day index), so it is right even if tonight's recount failed.
    const [{results: [live]}] = await db.batch([db.prepare(`SELECT day,COUNT(DISTINCT installation) AS active,SUM(plays) AS plays
      FROM ${activityTable(zone)} WHERE day=?1 GROUP BY day`).bind(window.start)]);
    if (live) await keepCohorts(db, zone, [windowDay(window, window.start, live)]);
  }
  const life = await countLifetime(db, at, (await keptCopy(db, 'lifetime'))?.value);
  await keepCopy(db, 'lifetime', life, at);
  hold(db, 'lifetime', { at, value: life }, at);
  // Copies of earlier days' pages and ranges.
  try { await db.prepare('DELETE FROM report_cache WHERE computed_at<?1').bind(at - 2 * DAY).run(); } catch { /* next night */ }
}

// Aggregate-only, no version breakdown or per-day series: safe to serve without
// a sign-in, for the README badge and a public "active this month" count.
// Counted across both tables: current apps report their days in India time
// (activity_ist), older ones in UTC (activity), and an installation is
// active if either has it. The windows follow [timeZone]'s calendar, the
// app's own by default; an old client's UTC day is at most 5.5 hours off it.
// Each figure reads only the rows it counts: the lifetime total is one row, and the week and
// month are the installations listed under them (kept by triggers as reports arrive).
export async function publicCounts(db, now = new Date(), timeZone = 'Asia/Kolkata') {
  reportZone(timeZone);
  const day = calendarDay(now, timeZone), yesterday = calendarDay(new Date(now.getTime() - DAY), timeZone);
  const week = new Date(day + 'T00:00:00Z');
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay() + 6) % 7));
  const activeOn = 'SELECT COUNT(*) AS n FROM (SELECT installation FROM activity WHERE day=? UNION SELECT installation FROM activity_ist WHERE day=?)';
  const results = await db.batch([
    db.prepare('SELECT installations AS n FROM totals WHERE id=1').bind(),
    db.prepare(activeOn).bind(day, day),
    db.prepare(activeOn).bind(yesterday, yesterday),
    db.prepare('SELECT COUNT(*) AS n FROM active_weeks WHERE week=?').bind(week.toISOString().slice(0, 10)),
    db.prepare('SELECT COUNT(*) AS n FROM active_months WHERE month=?').bind(day.slice(0, 7)),
  ]);
  return { asOf: now.toISOString(), timeZone,
    installations: results[0].results[0].n, daily: results[1].results[0].n, yesterday: results[2].results[0].n,
    weekly: results[3].results[0].n, monthly: results[4].results[0].n };
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
    // The whole of yesterday: a day's count is complete once it's over.
    : metric === 'yesterday' ? counts.yesterday
    : metric === 'weekly' ? counts.weekly
    : counts.monthly;
  const label = params.get('label') || (metric === 'installations' ? 'installs' : 'active');
  return { schemaVersion: 1, label, message: shortCount(value), color: '1db954', cacheSeconds: 3600 };
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

// The memory above is per instance, and Cloudflare starts new ones all the time, so on
// its own it let nearly every badge request count again. This copy lives in D1 and is
// shared by every instance: a request reads one row, and once a day one instance claims the
// refresh and counts again while the rest serve the copy. The day turns at 06:00 in [zone],
// not midnight, so the apps have sent in the evening's listening before yesterday is counted.
export const REFRESH_AFTER_MS = 6 * 60 * 60_000;
export const countDay = (at, zone) => calendarDay(new Date(at - REFRESH_AFTER_MS), zone);
// How long an instance keeps the copy in memory before reading the row again (one row).
export const PUBLIC_MEMORY_MS = 60 * 60_000;
const changed = result => (result?.meta?.changes ?? result?.changes ?? 0) > 0;
export async function sharedCounts(db, zone, now = Date.now()) {
  const [{results: [row]}] = await db.batch([db.prepare('SELECT payload, computed_at FROM public_snapshot WHERE zone=?').bind(zone)]);
  const saved = row?.payload ? JSON.parse(row.payload) : null;
  // A copy saved before yesterday's count existed is counted again.
  if (saved && 'yesterday' in saved && countDay(row.computed_at, zone) === countDay(now, zone)) return saved;
  // Claiming the recount is a write. When D1 refuses writes (the free plan's daily limit), the
  // last copy is still right to within a day: serve it rather than fail every badge until the reset.
  let claim;
  try {
    claim = row
      ? await db.prepare('UPDATE public_snapshot SET computed_at=? WHERE zone=? AND computed_at=?').bind(now, zone, row.computed_at).run()
      : await db.prepare("INSERT OR IGNORE INTO public_snapshot(zone,payload,computed_at) VALUES(?,'',?)").bind(zone, now).run();
  } catch (e) {
    if (saved) return saved;
    throw e;
  }
  if (!changed(claim) && saved) return saved;
  const counts = await publicCounts(db, new Date(now), zone);
  // Counted but not kept: still the right answer for this request.
  try { await db.prepare('UPDATE public_snapshot SET payload=?, computed_at=? WHERE zone=?').bind(JSON.stringify(counts), now, zone).run(); }
  catch { /* kept in this instance's memory by the caller, and counted again later */ }
  return counts;
}

// Each installation's day-by-day rows are kept for [KEEP_DAYS] (past the dashboard's longest range
// of 366 days); the lifetime figures and daily_totals keep everything. Run nightly by the cron in
// wrangler.jsonc, a few thousand rows at a time so a first clean-up of a long history spreads over
// some nights instead of spending a day's writes at once. Then the public counts, the last month
// of daily_totals and the dashboard's counts are worked out for the new day, so no visitor's
// request has to. Each step runs even if one before it failed (a clean-up is a write D1 may refuse).
export const KEEP_DAYS = 400;
const PRUNE_ROWS = 5000, PRUNE_ROUNDS = 2;
export async function housekeeping(db, now = new Date()) {
  const cutoff = calendarDay(new Date(now.getTime() - KEEP_DAYS * DAY), 'UTC');
  let removed = 0;
  try {
    for (const table of ['activity', 'activity_ist']) {
      for (let round = 0; round < PRUNE_ROUNDS; round++) {
        // By primary key, not rowid: the activity tables are WITHOUT ROWID (see schema.sql).
        const done = await db.prepare(`DELETE FROM ${table} WHERE (installation,day,version) IN
          (SELECT installation,day,version FROM ${table} WHERE day < ? LIMIT ${PRUNE_ROWS})`).bind(cutoff).run();
        const n = done?.meta?.changes ?? done?.changes ?? 0;
        removed += n;
        if (n < PRUNE_ROWS) break;
      }
    }
    await db.batch([
      db.prepare('DELETE FROM active_weeks WHERE week < ?').bind(cutoff),
      db.prepare('DELETE FROM active_months WHERE month < ?').bind(cutoff.slice(0, 7)),
    ]);
  } catch { /* tomorrow night */ }
  try { await sharedCounts(db, 'Asia/Kolkata', now.getTime()); } catch { /* counted at the first request instead */ }
  // Before the dashboard's counts, which read daily_totals. Refused or failed, the next night's
  // recount covers the same days again.
  let recounted = 0;
  try { recounted = await recountDailyTotals(db, now); } catch { /* tomorrow night */ }
  try { await refreshDashboard(db, now); } catch { /* counted at the first dashboard load instead */ }
  return { removed, cutoff, recounted };
}

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
  async scheduled(event, env, ctx) {
    ctx.waitUntil(housekeeping(env.DB, new Date(event.scheduledTime)));
  },
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
    // One line per signed-in dashboard read, for Workers Logs (wrangler.jsonc keeps only console
    // lines): who asked, never the token, cookie or address.
    if (request.method === 'GET' && ['/v1/summary', '/v1/analytics'].includes(path))
      console.log(JSON.stringify({ dashboard: path, query: url.search, origin: origin || '', userAgent: request.headers.get('User-Agent') || '',
        country: request.cf?.country || '', auth: request.headers.get('Authorization')?.startsWith('Bearer ') ? 'bearer' : 'cookie' }));
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
        // ?probe=1: signed in, the route and its parameters are fine; nothing is counted (the deploy check).
        if (url.searchParams.get('probe')==='1') return json({probe:true,range,timeZone:zone});
        const now=new Date();
        return json(await reuse(env.DB,`analytics:${zone}:${range.from}:${range.to}`,entry=>now-entry.at<REPORT_TTL_MS,
          ()=>analytics(env.DB,range,now,zone,{shared:true}),{now:now.getTime()}));
      }
      if (request.method==='GET' && path==='/v1/summary') {
        let zone;try { zone=reportZone(url.searchParams.get('timeZone') || 'UTC'); } catch(e) {return json({error:e.message},400);}
        if (url.searchParams.get('probe')==='1') return json({probe:true,timeZone:zone});
        // Kept for REPORT_TTL_MS, and only for this calendar day: opening the dashboard again reads one row.
        const now=new Date();
        return json(await reuse(env.DB,`summary:${zone}:${calendarDay(now,zone)}`,entry=>now-entry.at<REPORT_TTL_MS,
          ()=>summary(env.DB,now,zone,{shared:true}),{now:now.getTime()}));
      }
      if (request.method==='GET' && path==='/v1/public') {
        let zone;try { zone=reportZone(url.searchParams.get('timeZone') || 'Asia/Kolkata'); } catch(e) {return json({error:e.message},400);}
        return json(await rememberFor(env.DB, 'public:'+zone, PUBLIC_MEMORY_MS, () => sharedCounts(env.DB,zone)));
      }
      if (request.method==='GET' && path==='/v1/badge') {
        return json(badge(await rememberFor(env.DB, 'public:Asia/Kolkata', PUBLIC_MEMORY_MS, () => sharedCounts(env.DB,'Asia/Kolkata')),url.searchParams));
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

