-- STAGING ONLY: never run against shared musiclab-intelligence-db.
-- The sentinel is the evidence that DB points to isolated LAB46 D1.
CREATE TABLE IF NOT EXISTS lab46_staging_sentinel (
  marker TEXT PRIMARY KEY NOT NULL,
  initialized_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO lab46_staging_sentinel(marker)
VALUES('musiclab-turbo-lab46-isolated-d1-v1');
