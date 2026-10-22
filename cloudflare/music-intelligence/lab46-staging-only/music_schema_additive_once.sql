-- LAB46 ISOLATED STAGING ONLY / SINGLE APPLICATION. NO PRODUCTION D1.
-- VERIFY CLOUDFLARE ACCOUNT: 5781b597517f6f7fe25751170a99b9ab
-- VERIFY DATABASE NAME: musiclab-turbo-lab46-staging
-- VERIFY DATABASE UUID: e66bd500-cdaa-453c-a81c-c624a9d2611e
-- VERIFY EXISTING EXACT SENTINEL ROW: musiclab-turbo-lab46-isolated-d1-v1
-- PREREQUISITE: currently exactly the already-authorized staging tables
--   lab46_staging_sentinel and lab46_sync_receipts (plus SQLite internal tables).
-- Stop if the account, UUID, marker, tables, or current scope differ.
-- This schema is ADDITIVE: CREATE TABLE/INDEX and ALTER TABLE ADD COLUMN ONLY.
-- Historical 0002 destructive DELETE/UPDATE reset operations deliberately REMOVED.
-- Not automatically retryable after partial failure; stop and inspect first.
-- Never run on musiclab-intelligence-db, main or production.
-- No Worker deployment, credentials, billable upgrade, or remote script automation.

-- === SECTION 1: 0001 baseline safe schema ===
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS works (
  id TEXT PRIMARY KEY,
  search_key TEXT NOT NULL UNIQUE,
  canonical_title TEXT NOT NULL,
  original_artist TEXT NOT NULL,
  original_year INTEGER,
  original_language TEXT,
  credits_json TEXT,
  ai_model TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS versions (
  id TEXT PRIMARY KEY,
  work_id TEXT NOT NULL,
  version_key TEXT NOT NULL,
  canonical_title TEXT NOT NULL,
  canonical_artist TEXT NOT NULL,
  category TEXT NOT NULL,
  language TEXT,
  year INTEGER,
  album TEXT,
  credits_json TEXT,
  ai_model TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE,
  UNIQUE(work_id, version_key)
);

CREATE TABLE IF NOT EXISTS playback_bindings (
  playback_id TEXT PRIMARY KEY,
  version_id TEXT,
  work_id TEXT,
  source_kind TEXT NOT NULL DEFAULT 'youtube',
  last_verified_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(version_id) REFERENCES versions(id) ON DELETE SET NULL,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS artists (
  artist_key TEXT PRIMARY KEY,
  canonical_name TEXT NOT NULL,
  youtube_browse_id TEXT,
  last_verified_at TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS albums (
  album_key TEXT PRIMARY KEY,
  canonical_title TEXT NOT NULL,
  canonical_artist TEXT NOT NULL,
  youtube_browse_id TEXT,
  year INTEGER,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS research_state (
  work_id TEXT NOT NULL,
  family TEXT NOT NULL,
  cursor TEXT,
  empty_rounds INTEGER NOT NULL DEFAULT 0,
  completed INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(work_id, family),
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS requests_log (
  id TEXT PRIMARY KEY,
  search_key TEXT,
  operation TEXT NOT NULL,
  cache_hit INTEGER NOT NULL DEFAULT 0,
  result_count INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  engine_version TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_versions_work_category ON versions(work_id, category);
CREATE INDEX IF NOT EXISTS idx_versions_artist ON versions(canonical_artist);
CREATE INDEX IF NOT EXISTS idx_playback_work ON playback_bindings(work_id);

-- === SECTION 2: 0002 safe resolver column & index; HISTORIC RESETS EXCLUDED ===
PRAGMA foreign_keys = ON;
ALTER TABLE works ADD COLUMN resolver_version INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_works_resolver_version ON works(resolver_version);

-- === SECTION 3: 0003 MusicLab brain additive schema ===
PRAGMA foreign_keys = ON;

-- LAB20 MusicLab AI Brain: additive memory/evidence schema.
-- No existing works/versions are deleted or reset.

ALTER TABLE works ADD COLUMN work_signature_json TEXT;
ALTER TABLE works ADD COLUMN iswc TEXT;
ALTER TABLE works ADD COLUMN musicbrainz_work_id TEXT;
ALTER TABLE works ADD COLUMN ipi_json TEXT;

ALTER TABLE versions ADD COLUMN same_work_score INTEGER NOT NULL DEFAULT 50 CHECK(same_work_score BETWEEN 0 AND 100);
ALTER TABLE versions ADD COLUMN version_type_score INTEGER NOT NULL DEFAULT 50 CHECK(version_type_score BETWEEN 0 AND 100);
ALTER TABLE versions ADD COLUMN decision_status TEXT NOT NULL DEFAULT 'UNCERTAIN' CHECK(decision_status IN ('APPROVED','PROBABLE','UNCERTAIN','REJECTED'));
ALTER TABLE versions ADD COLUMN ai_reason TEXT;
ALTER TABLE versions ADD COLUMN user_verified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE versions ADD COLUMN user_rejected INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS work_aliases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  work_id TEXT NOT NULL,
  alias TEXT NOT NULL,
  language TEXT,
  country TEXT,
  alias_kind TEXT NOT NULL DEFAULT 'alternate' CHECK(alias_kind IN ('canonical','alternate','translated','adapted','literal_hint')),
  source TEXT,
  confidence INTEGER NOT NULL DEFAULT 50 CHECK(confidence BETWEEN 0 AND 100),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS related_works (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  work_id TEXT NOT NULL,
  related_work_external_id TEXT,
  related_title TEXT,
  language TEXT,
  country TEXT,
  relation_kind TEXT NOT NULL DEFAULT 'adaptation',
  translated INTEGER NOT NULL DEFAULT 0,
  evidence_json TEXT,
  confidence INTEGER NOT NULL DEFAULT 50 CHECK(confidence BETWEEN 0 AND 100),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS version_evidence (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id TEXT NOT NULL,
  work_id TEXT NOT NULL,
  source TEXT NOT NULL,
  signal_kind TEXT NOT NULL,
  strength TEXT NOT NULL DEFAULT 'medium' CHECK(strength IN ('very_strong','strong','medium','weak','none')),
  direction TEXT NOT NULL DEFAULT 'positive' CHECK(direction IN ('positive','negative','neutral')),
  external_id TEXT,
  source_url TEXT,
  note TEXT,
  payload_json TEXT,
  observed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(version_id) REFERENCES versions(id) ON DELETE CASCADE,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS decision_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id TEXT NOT NULL,
  work_id TEXT NOT NULL,
  decision_status TEXT NOT NULL CHECK(decision_status IN ('APPROVED','PROBABLE','UNCERTAIN','REJECTED')),
  same_work_score INTEGER CHECK(same_work_score BETWEEN 0 AND 100),
  version_type_score INTEGER CHECK(version_type_score BETWEEN 0 AND 100),
  decided_by TEXT NOT NULL DEFAULT 'ai' CHECK(decided_by IN ('ai','user','system')),
  reason TEXT,
  evidence_snapshot_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(version_id) REFERENCES versions(id) ON DELETE CASCADE,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS search_missions (
  id TEXT PRIMARY KEY,
  work_id TEXT NOT NULL,
  family TEXT NOT NULL,
  dimension_key TEXT NOT NULL,
  query_language TEXT,
  target_language TEXT,
  strategy TEXT,
  query_text TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','running','completed','empty','failed')),
  result_count INTEGER NOT NULL DEFAULT 0,
  unique_result_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS coverage_cells (
  work_id TEXT NOT NULL,
  family TEXT NOT NULL,
  dimension_key TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'unsearched' CHECK(state IN ('unsearched','in_flight','searched','empty')),
  result_count INTEGER NOT NULL DEFAULT 0,
  last_mission_id TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(work_id, family, dimension_key),
  FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_works_iswc ON works(iswc);
CREATE INDEX IF NOT EXISTS idx_works_mb_work ON works(musicbrainz_work_id);
CREATE INDEX IF NOT EXISTS idx_versions_status_scores ON versions(decision_status, same_work_score, version_type_score);
CREATE UNIQUE INDEX IF NOT EXISTS idx_aliases_unique ON work_aliases(work_id, alias, COALESCE(language,''), alias_kind);
CREATE INDEX IF NOT EXISTS idx_aliases_work_language ON work_aliases(work_id, language);
CREATE INDEX IF NOT EXISTS idx_related_works_work ON related_works(work_id);
CREATE INDEX IF NOT EXISTS idx_evidence_version ON version_evidence(version_id);
CREATE INDEX IF NOT EXISTS idx_evidence_work_source ON version_evidence(work_id, source);
CREATE INDEX IF NOT EXISTS idx_decision_history_version ON decision_history(version_id, created_at);
CREATE INDEX IF NOT EXISTS idx_search_missions_work_family ON search_missions(work_id, family, status);
CREATE INDEX IF NOT EXISTS idx_coverage_work_family ON coverage_cells(work_id, family, state);

-- === SECTION 4: 0004 LAB39 cover artwork additive column ===
PRAGMA foreign_keys = ON;

-- LAB39: persist artwork for approved cloud versions.
-- Playback ids already use the playback_bindings table created in 0001.
ALTER TABLE versions ADD COLUMN cover_url TEXT;

-- === SECTION 5: 0005 LAB46 additive archive columns ===
-- MusicLab Turbo LAB46, additive compatibility migration (DO NOT apply to shared D1).
-- A staging-only dry-run must precede any explicitly authorized remote migration.
-- NULL means unverified/unavailable; never fabricate missing metadata.
ALTER TABLE versions ADD COLUMN release_date TEXT;
ALTER TABLE versions ADD COLUMN country TEXT;
ALTER TABLE versions ADD COLUMN source_names_json TEXT;
ALTER TABLE versions ADD COLUMN source_url TEXT;
-- Complete performer/producer/engineer credits, with role preserved.
ALTER TABLE versions ADD COLUMN credits_detail_json TEXT;
ALTER TABLE versions ADD COLUMN video_title TEXT;
ALTER TABLE versions ADD COLUMN recording_fingerprint TEXT;
ALTER TABLE versions ADD COLUMN legacy_score_20 INTEGER CHECK(legacy_score_20 BETWEEN 1 AND 20);
ALTER TABLE versions ADD COLUMN musiclab_score_100 INTEGER CHECK(musiclab_score_100 BETWEEN 0 AND 100);
ALTER TABLE versions ADD COLUMN cover_round INTEGER CHECK(cover_round >= 1);
ALTER TABLE versions ADD COLUMN round_position INTEGER CHECK(round_position >= 1);
ALTER TABLE versions ADD COLUMN work_relation_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(work_relation_confirmed IN (0,1));
-- User approval remains in existing user_verified / decision_history fields;
-- no approval can set work_relation_confirmed by itself.
CREATE INDEX IF NOT EXISTS idx_versions_work_round_position
  ON versions(work_id,cover_round,round_position);
