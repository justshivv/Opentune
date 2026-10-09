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
CREATE TABLE IF NOT EXISTS downloads (
  browser TEXT NOT NULL,
  release TEXT NOT NULL,
  first_requested_at TEXT NOT NULL,
  PRIMARY KEY (browser, release)
);
