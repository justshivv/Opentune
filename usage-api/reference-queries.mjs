// The dashboard's queries from before the nightly counts (see "The owner's dashboard" in
// worker.mjs), kept only so worker.test.mjs can check the new figures against them. Each reads
// every activity row in its range: never call these from the Worker. The day-by-day figures, lifetime
// plays and first day are counted from the activity rows themselves, as the triggers used to keep
// daily_totals: what the nightly recount and the live days have to add up to.
import { calendarDay, currentVersionsSql } from './worker.mjs';
const DAY = 86400000;
const reportZone = value => { if (!['UTC', 'Asia/Kolkata'].includes(value)) throw new Error('Unsupported reporting time zone'); return value; };
const activityTable = zone => reportZone(zone) === 'Asia/Kolkata' ? 'activity_ist' : 'activity';

/** First observed activity, not APK downloads or unverifiable unique people. */
async function cohorts(db, from, to, timeZone = 'UTC') {
  const table = activityTable(timeZone);
  // installations holds each one's first day, kept by triggers: no scan of all history.
  const [result] = await db.batch([db.prepare(`WITH daily_install AS (
    SELECT installation, day, SUM(plays) AS plays FROM ${table}
    WHERE day BETWEEN ? AND ? GROUP BY installation, day
  ) SELECT d.day,
    SUM(CASE WHEN f.first_day=d.day THEN 1 ELSE 0 END) AS newInstallations,
    SUM(CASE WHEN f.first_day<d.day THEN 1 ELSE 0 END) AS returningInstallations,
    SUM(CASE WHEN f.first_day=d.day THEN d.plays ELSE 0 END) AS newPlays,
    SUM(CASE WHEN f.first_day<d.day THEN d.plays ELSE 0 END) AS returningPlays,
    SUM(CASE WHEN d.plays>0 THEN 1 ELSE 0 END) AS listeners
    FROM daily_install d JOIN installations f USING(installation)
    GROUP BY d.day ORDER BY d.day`).bind(from, to)]);
  return result.results;
}

/**
 * The period as a whole, split by when each installation was first seen: new ones were first
 * seen within [from, to], returning ones before it. Together they make the period's unique
 * active installations, each counted once.
 */
async function periodCohorts(db, from, to, timeZone = 'UTC') {
  const table = activityTable(timeZone);
  const [result] = await db.batch([db.prepare(`WITH period AS (
    SELECT installation, SUM(plays) AS plays FROM ${table}
    WHERE day BETWEEN ? AND ? GROUP BY installation
  ) SELECT
    COALESCE(SUM(CASE WHEN f.first_day>=? THEN 1 ELSE 0 END),0) AS newInstallations,
    COALESCE(SUM(CASE WHEN f.first_day<? THEN 1 ELSE 0 END),0) AS returningInstallations,
    COALESCE(SUM(CASE WHEN f.first_day>=? THEN p.plays ELSE 0 END),0) AS newPlays,
    COALESCE(SUM(CASE WHEN f.first_day<? THEN p.plays ELSE 0 END),0) AS returningPlays
    FROM period p JOIN installations f USING(installation)`).bind(from, to, from, from, from, from)]);
  return result.results[0];
}

export async function oldSummary(db, now = new Date(), timeZone = 'UTC') {
  const table = activityTable(timeZone);
  const day = calendarDay(now,timeZone);
  const week = new Date(day+'T00:00:00Z');
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay()+6)%7));
  const month = day.slice(0,7)+'-01';
  const from = calendarDay(new Date(now.getTime()-29*DAY),timeZone);
  const results = await db.batch([
    // Lifetime installations come from the count a trigger keeps.
    db.prepare(`SELECT t.installations AS installs,
      (SELECT COALESCE(SUM(plays),0) FROM activity) + (SELECT COALESCE(SUM(plays),0) FROM activity_ist) AS plays,
      (SELECT MIN(day) FROM (SELECT day FROM activity UNION ALL SELECT day FROM activity_ist)) AS since
      FROM totals t WHERE t.id=1`),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active, COALESCE(SUM(plays),0) AS plays FROM ${table} WHERE day=?`).bind(day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active FROM ${table} WHERE day>=? AND day<=?`).bind(week.toISOString().slice(0,10),day),
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active FROM ${table} WHERE day>=? AND day<=?`).bind(month,day),
    db.prepare(`SELECT day, COUNT(DISTINCT installation) AS active, SUM(plays) AS plays FROM ${table} WHERE day>=? AND day<=? GROUP BY day ORDER BY day`).bind(from,day),
    db.prepare(`SELECT s.version,(SELECT COUNT(*) FROM version_installs v WHERE v.version=s.version) AS installations
      FROM versions_seen s ORDER BY s.last_day DESC`),
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

export async function oldAnalytics(db, range, now = new Date(), timeZone = 'UTC') {
  const { from, to } = range;
  const table = activityTable(timeZone);
  const results = await db.batch([
    db.prepare(`SELECT COUNT(DISTINCT installation) AS active, COALESCE(SUM(plays),0) AS plays FROM ${table} WHERE day BETWEEN ? AND ?`).bind(from,to),
    db.prepare(`SELECT day, COUNT(DISTINCT installation) AS active, SUM(plays) AS plays FROM ${table} WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day`).bind(from,to),
    db.prepare(`SELECT version, COUNT(DISTINCT installation) AS installations, SUM(plays) AS plays FROM ${table} WHERE day BETWEEN ? AND ? GROUP BY version ORDER BY installations DESC, version DESC`).bind(from,to),
    db.prepare(`SELECT MIN(day) AS since FROM ${table}`),
  ]);
  return { asOf: now.toISOString(), range, timeZone, totals: results[0].results[0],
    split: await periodCohorts(db, from, to, timeZone),
    daily: results[1].results, cohorts: await cohorts(db, from, to, timeZone), versions: results[2].results,
    coverageSince: results[3].results[0].since };
}
