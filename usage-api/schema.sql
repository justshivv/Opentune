-- WITHOUT ROWID: the primary key is the table, so a new row is one row written plus the day
-- index, not also a separate key index. Databases made before this are rebuilt once by
-- migrations/without-rowid-activity.sql and -activity_ist.sql.
CREATE TABLE IF NOT EXISTS activity (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS activity_day ON activity(day, installation);
-- No query reads by version through this table any more (version_installs does), and every
-- index costs a row written per report: D1's free plan allows 100,000 rows written a day.
DROP INDEX IF EXISTS activity_version;
-- UTC daily totals cannot be shifted accurately after collection. Keep the
-- legacy table and collect new IST days separately, without rewriting history.
CREATE TABLE IF NOT EXISTS activity_ist (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS activity_ist_day ON activity_ist(day, installation);
-- No query reads by version through this table any more (version_installs does), and every
-- index costs a row written per report: D1's free plan allows 100,000 rows written a day.
DROP INDEX IF EXISTS activity_ist_version;
CREATE TABLE IF NOT EXISTS installation_versions (
  installation TEXT PRIMARY KEY,
  version TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision >= 1),
  received_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS downloads (
  browser TEXT NOT NULL,
  release TEXT NOT NULL,
  first_requested_at TEXT NOT NULL,
  PRIMARY KEY (browser, release)
);

-- Public community addon catalog; only the authenticated owner can modify it.
CREATE TABLE IF NOT EXISTS addon_catalog (
  id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload TEXT NOT NULL
);

-- Version changes, one row per move, recorded by a trigger when an installation's
-- current version changes. The version breakdown above already moves an
-- installation's count from its old version to its new one; this counts those
-- moves, so an update is never mistaken for a new installation. A retry at the
-- same revision does not update the row, so it records nothing.
CREATE TABLE IF NOT EXISTS version_transitions (
  installation TEXT NOT NULL,
  from_version TEXT NOT NULL,
  to_version TEXT NOT NULL,
  changed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS version_transitions_to ON version_transitions(to_version);
CREATE INDEX IF NOT EXISTS version_transitions_at ON version_transitions(changed_at);
CREATE TRIGGER IF NOT EXISTS record_version_change
AFTER UPDATE OF version ON installation_versions
FOR EACH ROW WHEN old.version <> new.version
BEGIN
  INSERT INTO version_transitions(installation, from_version, to_version, changed_at)
  VALUES (new.installation, old.version, new.version, new.received_at);
END;

-- ---------------------------------------------------------------------------
-- Summaries kept up to date as reports arrive, so the public counts, the README
-- badges and the dashboard read a handful of rows instead of scanning every
-- activity row each time. D1's free plan allows 5 million rows read a day, and
-- full scans of both activity tables on every badge request used it all.
-- Everything below is additive and safe to run again on each deploy.

-- Each installation and the first day it reported: lifetime installations and
-- new-versus-returning splits come from here.
CREATE TABLE IF NOT EXISTS installations (
  installation TEXT PRIMARY KEY,
  first_day TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS installations_first_day ON installations(first_day);

-- Lifetime totals, one row.
CREATE TABLE IF NOT EXISTS totals (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  installations INTEGER NOT NULL,
  plays INTEGER NOT NULL
);
-- First created with the plays already recorded; after that the triggers keep it. The sums
-- are only worked out while there's no row: this file runs on every deploy.
INSERT INTO totals(id, installations, plays)
  SELECT 1, 0, (SELECT COALESCE(SUM(plays), 0) FROM activity) + (SELECT COALESCE(SUM(plays), 0) FROM activity_ist)
  WHERE NOT EXISTS (SELECT 1 FROM totals);

-- Who was active in each calendar week (by its Monday) and month, across both tables.
CREATE TABLE IF NOT EXISTS active_weeks (week TEXT NOT NULL, installation TEXT NOT NULL, PRIMARY KEY (week, installation));
CREATE TABLE IF NOT EXISTS active_months (month TEXT NOT NULL, installation TEXT NOT NULL, PRIMARY KEY (month, installation));

-- Each version an installation has reported, and the last day each version was seen.
CREATE TABLE IF NOT EXISTS version_installs (version TEXT NOT NULL, installation TEXT NOT NULL, PRIMARY KEY (version, installation));
CREATE TABLE IF NOT EXISTS versions_seen (version TEXT PRIMARY KEY, last_day TEXT NOT NULL);

-- A cached copy of the public counts, shared by every Worker instance.
CREATE TABLE IF NOT EXISTS public_snapshot (
  zone TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  computed_at INTEGER NOT NULL
);

CREATE TRIGGER IF NOT EXISTS count_installation AFTER INSERT ON installations
BEGIN
  UPDATE totals SET installations = installations + 1 WHERE id = 1;
END;

DROP TRIGGER IF EXISTS summarize_activity_insert;
CREATE TRIGGER summarize_activity_insert AFTER INSERT ON activity
BEGIN
  INSERT OR IGNORE INTO installations(installation, first_day) VALUES (new.installation, new.day);
  UPDATE installations SET first_day = new.day WHERE installation = new.installation AND first_day > new.day;
  INSERT OR IGNORE INTO active_weeks(week, installation) VALUES (date(new.day, '-6 days', 'weekday 1'), new.installation);
  INSERT OR IGNORE INTO active_months(month, installation) VALUES (substr(new.day, 1, 7), new.installation);
  INSERT OR IGNORE INTO version_installs(version, installation) VALUES (new.version, new.installation);
  INSERT OR IGNORE INTO versions_seen(version, last_day) VALUES (new.version, new.day);
  UPDATE versions_seen SET last_day = new.day WHERE version = new.version AND last_day < new.day;
END;
DROP TRIGGER IF EXISTS summarize_activity_ist_insert;
CREATE TRIGGER summarize_activity_ist_insert AFTER INSERT ON activity_ist
BEGIN
  INSERT OR IGNORE INTO installations(installation, first_day) VALUES (new.installation, new.day);
  UPDATE installations SET first_day = new.day WHERE installation = new.installation AND first_day > new.day;
  INSERT OR IGNORE INTO active_weeks(week, installation) VALUES (date(new.day, '-6 days', 'weekday 1'), new.installation);
  INSERT OR IGNORE INTO active_months(month, installation) VALUES (substr(new.day, 1, 7), new.installation);
  INSERT OR IGNORE INTO version_installs(version, installation) VALUES (new.version, new.installation);
  INSERT OR IGNORE INTO versions_seen(version, last_day) VALUES (new.version, new.day);
  UPDATE versions_seen SET last_day = new.day WHERE version = new.version AND last_day < new.day;
END;
-- Lifetime plays are no longer kept in totals: they are the sum of daily_totals, read only by the
-- owner's dashboard. Keeping them here cost a row written on every report.
DROP TRIGGER IF EXISTS summarize_activity_plays;
DROP TRIGGER IF EXISTS summarize_activity_ist_plays;

-- Backfill from what was recorded before these tables existed, once. This file runs on every
-- deploy, and each of these reads every activity row; the marker in backfills makes the later
-- runs read nothing (the empty "go" row comes first, so the scans never start). INSERT OR
-- IGNORE leaves rows already there alone; count_installation counts each installation added.
CREATE TABLE IF NOT EXISTS backfills (name TEXT PRIMARY KEY);
INSERT OR IGNORE INTO installations(installation, first_day)
  SELECT installation, MIN(day) FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'summaries')) AS go
  CROSS JOIN (SELECT installation, day FROM activity UNION ALL SELECT installation, day FROM activity_ist) GROUP BY installation;
INSERT OR IGNORE INTO active_weeks(week, installation)
  SELECT week, installation FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'summaries')) AS go
  CROSS JOIN (SELECT date(day, '-6 days', 'weekday 1') AS week, installation FROM activity
    UNION SELECT date(day, '-6 days', 'weekday 1'), installation FROM activity_ist);
INSERT OR IGNORE INTO active_months(month, installation)
  SELECT month, installation FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'summaries')) AS go
  CROSS JOIN (SELECT substr(day, 1, 7) AS month, installation FROM activity UNION SELECT substr(day, 1, 7), installation FROM activity_ist);
INSERT OR IGNORE INTO version_installs(version, installation)
  SELECT version, installation FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'summaries')) AS go
  CROSS JOIN (SELECT version, installation FROM activity UNION SELECT version, installation FROM activity_ist);
INSERT OR IGNORE INTO versions_seen(version, last_day)
  SELECT version, MAX(day) FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'summaries')) AS go
  CROSS JOIN (SELECT version, day FROM activity UNION ALL SELECT version, day FROM activity_ist) GROUP BY version;
INSERT OR IGNORE INTO backfills(name) VALUES ('summaries');

-- One row per day and reporting zone ('UTC' is the activity table, 'Asia/Kolkata' activity_ist):
-- how many installations were active and how many plays they had. The dashboard's day-by-day
-- figures read these few rows instead of every report in the range. Kept for good, so the daily
-- chart outlives the per-installation rows the nightly clean-up removes after 400 days.
CREATE TABLE IF NOT EXISTS daily_totals (
  zone TEXT NOT NULL,
  day TEXT NOT NULL,
  installations INTEGER NOT NULL,
  plays INTEGER NOT NULL,
  PRIMARY KEY (zone, day)
);
-- A new row is a new installation for the day unless that installation already has the day under
-- another version (an update mid-day): one primary-key lookup.
CREATE TRIGGER IF NOT EXISTS daily_activity_insert AFTER INSERT ON activity
BEGIN
  INSERT OR IGNORE INTO daily_totals(zone, day, installations, plays) VALUES ('UTC', new.day, 0, 0);
  UPDATE daily_totals SET plays = plays + new.plays,
    installations = installations + NOT EXISTS (SELECT 1 FROM activity a WHERE a.installation = new.installation AND a.day = new.day AND a.version <> new.version)
  WHERE zone = 'UTC' AND day = new.day;
END;
CREATE TRIGGER IF NOT EXISTS daily_activity_ist_insert AFTER INSERT ON activity_ist
BEGIN
  INSERT OR IGNORE INTO daily_totals(zone, day, installations, plays) VALUES ('Asia/Kolkata', new.day, 0, 0);
  UPDATE daily_totals SET plays = plays + new.plays,
    installations = installations + NOT EXISTS (SELECT 1 FROM activity_ist a WHERE a.installation = new.installation AND a.day = new.day AND a.version <> new.version)
  WHERE zone = 'Asia/Kolkata' AND day = new.day;
END;
CREATE TRIGGER IF NOT EXISTS daily_activity_plays AFTER UPDATE OF plays ON activity
BEGIN
  UPDATE daily_totals SET plays = plays + new.plays - old.plays WHERE zone = 'UTC' AND day = new.day;
END;
CREATE TRIGGER IF NOT EXISTS daily_activity_ist_plays AFTER UPDATE OF plays ON activity_ist
BEGIN
  UPDATE daily_totals SET plays = plays + new.plays - old.plays WHERE zone = 'Asia/Kolkata' AND day = new.day;
END;
-- Built once from the history already recorded, the same way as the summaries above.
INSERT OR IGNORE INTO daily_totals(zone, day, installations, plays)
  SELECT 'UTC', day, COUNT(DISTINCT installation), SUM(plays)
  FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'daily-totals')) AS go CROSS JOIN activity GROUP BY day;
INSERT OR IGNORE INTO daily_totals(zone, day, installations, plays)
  SELECT 'Asia/Kolkata', day, COUNT(DISTINCT installation), SUM(plays)
  FROM (SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM backfills WHERE name = 'daily-totals')) AS go CROSS JOIN activity_ist GROUP BY day;
INSERT OR IGNORE INTO backfills(name) VALUES ('daily-totals');

-- For the owner's dashboard (see "The owner's dashboard" in worker.mjs). Everything here is counted
-- from the tables above, so losing it costs only a recount. WITHOUT ROWID: a row written is one
-- row, with no separate index to write.
-- Each final day's new and returning installations, their plays and listeners. A day is final
-- once reports for it are no longer accepted (after 30 days); its row is written once, by the
-- nightly cron or the first time a dashboard range shows that day.
CREATE TABLE IF NOT EXISTS daily_cohorts (
  zone TEXT NOT NULL,
  day TEXT NOT NULL,
  new_installations INTEGER NOT NULL,
  returning_installations INTEGER NOT NULL,
  new_plays INTEGER NOT NULL,
  returning_plays INTEGER NOT NULL,
  listeners INTEGER NOT NULL,
  PRIMARY KEY (zone, day)
) WITHOUT ROWID;
-- Counts and responses shared by every Worker instance: the nightly counts (window:<zone> and
-- lifetime) and dashboard responses, kept for half an hour. Rows older than two days are deleted
-- nightly.
CREATE TABLE IF NOT EXISTS report_cache (
  key TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  computed_at INTEGER NOT NULL
) WITHOUT ROWID;
