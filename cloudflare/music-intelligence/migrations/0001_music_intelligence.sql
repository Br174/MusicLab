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
