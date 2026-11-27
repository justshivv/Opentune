-- One-off: rebuild activity as WITHOUT ROWID (see the note at the top of schema.sql). Run by the
-- "Usage database migration" workflow right after D1's daily reset (05:30 IST), followed by
-- schema.sql in the same file, so the triggers and the day index are back in the same
-- transaction: no report is recorded without them. Costs about two rows written per row
-- copied (the row and its day index entry).
CREATE TABLE activity_rebuilt (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
) WITHOUT ROWID;
INSERT INTO activity_rebuilt(installation, day, version, plays, received_at)
  SELECT installation, day, version, plays, received_at FROM activity;
DROP TABLE activity;
ALTER TABLE activity_rebuilt RENAME TO activity;
