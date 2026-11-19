CREATE TABLE IF NOT EXISTS activity (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
);
CREATE INDEX IF NOT EXISTS activity_day ON activity(day, installation);
CREATE INDEX IF NOT EXISTS activity_version ON activity(version, installation);
-- UTC daily totals cannot be shifted accurately after collection. Keep the
-- legacy table and collect new IST days separately, without rewriting history.
CREATE TABLE IF NOT EXISTS activity_ist (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
);
CREATE INDEX IF NOT EXISTS activity_ist_day ON activity_ist(day, installation);
CREATE INDEX IF NOT EXISTS activity_ist_version ON activity_ist(version, installation);
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
-- First created with the plays already recorded; after that the triggers keep it.
INSERT OR IGNORE INTO totals(id, installations, plays) VALUES (1, 0,
  (SELECT COALESCE(SUM(plays), 0) FROM activity) + (SELECT COALESCE(SUM(plays), 0) FROM activity_ist));

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

CREATE TRIGGER IF NOT EXISTS summarize_activity_insert AFTER INSERT ON activity
BEGIN
  INSERT OR IGNORE INTO installations(installation, first_day) VALUES (new.installation, new.day);
  UPDATE installations SET first_day = new.day WHERE installation = new.installation AND first_day > new.day;
  UPDATE totals SET plays = plays + new.plays WHERE id = 1;
  INSERT OR IGNORE INTO active_weeks(week, installation) VALUES (date(new.day, '-6 days', 'weekday 1'), new.installation);
  INSERT OR IGNORE INTO active_months(month, installation) VALUES (substr(new.day, 1, 7), new.installation);
  INSERT OR IGNORE INTO version_installs(version, installation) VALUES (new.version, new.installation);
  INSERT OR IGNORE INTO versions_seen(version, last_day) VALUES (new.version, new.day);
  UPDATE versions_seen SET last_day = new.day WHERE version = new.version AND last_day < new.day;
END;
CREATE TRIGGER IF NOT EXISTS summarize_activity_ist_insert AFTER INSERT ON activity_ist
BEGIN
  INSERT OR IGNORE INTO installations(installation, first_day) VALUES (new.installation, new.day);
  UPDATE installations SET first_day = new.day WHERE installation = new.installation AND first_day > new.day;
  UPDATE totals SET plays = plays + new.plays WHERE id = 1;
  INSERT OR IGNORE INTO active_weeks(week, installation) VALUES (date(new.day, '-6 days', 'weekday 1'), new.installation);
  INSERT OR IGNORE INTO active_months(month, installation) VALUES (substr(new.day, 1, 7), new.installation);
  INSERT OR IGNORE INTO version_installs(version, installation) VALUES (new.version, new.installation);
  INSERT OR IGNORE INTO versions_seen(version, last_day) VALUES (new.version, new.day);
  UPDATE versions_seen SET last_day = new.day WHERE version = new.version AND last_day < new.day;
END;
-- A report raises a day's plays (never lowers them): the difference goes on the total.
CREATE TRIGGER IF NOT EXISTS summarize_activity_plays AFTER UPDATE OF plays ON activity
BEGIN
  UPDATE totals SET plays = plays + new.plays - old.plays WHERE id = 1;
END;
CREATE TRIGGER IF NOT EXISTS summarize_activity_ist_plays AFTER UPDATE OF plays ON activity_ist
BEGIN
  UPDATE totals SET plays = plays + new.plays - old.plays WHERE id = 1;
END;

-- Backfill from what was recorded before these tables existed. INSERT OR IGNORE
-- leaves rows already there alone, so running this again changes nothing; the
-- count_installation trigger counts each installation as it's added.
INSERT OR IGNORE INTO installations(installation, first_day)
  SELECT installation, MIN(day) FROM (SELECT installation, day FROM activity UNION ALL SELECT installation, day FROM activity_ist) GROUP BY installation;
INSERT OR IGNORE INTO active_weeks(week, installation)
  SELECT date(day, '-6 days', 'weekday 1'), installation FROM activity UNION SELECT date(day, '-6 days', 'weekday 1'), installation FROM activity_ist;
INSERT OR IGNORE INTO active_months(month, installation)
  SELECT substr(day, 1, 7), installation FROM activity UNION SELECT substr(day, 1, 7), installation FROM activity_ist;
INSERT OR IGNORE INTO version_installs(version, installation)
  SELECT version, installation FROM activity UNION SELECT version, installation FROM activity_ist;
INSERT OR IGNORE INTO versions_seen(version, last_day)
  SELECT version, MAX(day) FROM (SELECT version, day FROM activity UNION ALL SELECT version, day FROM activity_ist) GROUP BY version;
