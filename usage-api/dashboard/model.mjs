export const DAY = 86400000;
export const isoDay = (date, timeZone = 'Asia/Kolkata') => new Intl.DateTimeFormat('en-CA', {timeZone, year:'numeric', month:'2-digit', day:'2-digit'}).format(date);
export function presetRange(days, now = new Date(), timeZone = 'Asia/Kolkata') {
  const to = isoDay(now, timeZone);
  return { from: isoDay(new Date(Date.parse(to) - (days - 1) * DAY)), to };
}
export function rangeError({ from, to }, now = new Date(), timeZone = 'Asia/Kolkata') {
  const valid = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') && Number.isFinite(Date.parse(value)) && isoDay(new Date(value)) === value;
  if (!valid(from) || !valid(to)) return 'Choose a valid start and end date.';
  if (from > to) return 'The start date must be before or equal to the end date.';
  if (to > isoDay(now, timeZone)) return `Choose today or an earlier date (${timeZone === 'Asia/Kolkata' ? 'IST' : 'UTC'}).`;
  if ((Date.parse(to) - Date.parse(from)) / DAY >= 366) return 'Choose up to 366 days at a time.';
  return '';
}
// Zero means no reports received during collection, never a failed request.
// Each day's active installations are split into new (first seen that day) and returning
// (seen before), when the server reports the split; together they make the day's active count.
export function dailyRows(data) {
  const byDay = new Map(data.daily.map(row => [row.day, row]));
  const cohorts = Array.isArray(data.cohorts) ? new Map(data.cohorts.map(row => [row.day, row])) : null;
  const rows = [];
  for (let time = Date.parse(data.range.from); time <= Date.parse(data.range.to); time += DAY) {
    const day = isoDay(new Date(time));
    const covered = Boolean(data.coverageSince && day >= data.coverageSince);
    const row = { day, active: covered ? 0 : null, plays: covered ? 0 : null, ...byDay.get(day) };
    if (cohorts) {
      const c = cohorts.get(day);
      row.newInstallations = c ? c.newInstallations : covered ? 0 : null;
      row.returningInstallations = c ? c.returningInstallations : covered ? 0 : null;
    }
    rows.push(row);
  }
  return rows;
}
// Older workers only expose 30 days. Never sum daily users into unique users.
export function legacyAnalytics(summary, range) {
  const available = presetRange(30, new Date(summary.asOf), 'UTC');
  if (range.from < available.from || range.to > available.to) throw new Error('This date range needs the analytics API update. The current server exposes only the last 30 days.');
  const daily = summary.daily.filter(row => row.day >= range.from && row.day <= range.to);
  return { range, daily, coverageSince: summary.totals.since, versions: null,
    totals: { active: range.from === range.to ? (daily[0]?.active ?? 0) : null, plays: daily.reduce((sum, row) => sum + row.plays, 0) },
    limited: true, asOf: summary.asOf };
}
