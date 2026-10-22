-- STAGING ONLY: never run against shared musiclab-intelligence-db.
-- The sentinel is the evidence that DB points to isolated LAB46 D1.
CREATE TABLE IF NOT EXISTS lab46_staging_sentinel (
  marker TEXT PRIMARY KEY NOT NULL,
  initialized_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO lab46_staging_sentinel(marker)
VALUES('musiclab-turbo-lab46-isolated-d1-v1');

-- LAB46 isolated staging-only replay/audit ledger. No user identifiers,
-- production migrations or shared D1 database modifications.
CREATE TABLE IF NOT EXISTS lab46_sync_receipts (
  revision TEXT PRIMARY KEY NOT NULL,
  fingerprint TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('pending','committed','failed')),
  version_id TEXT,
  first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lab46_sync_receipts_fingerprint
ON lab46_sync_receipts(fingerprint,updated_at);
