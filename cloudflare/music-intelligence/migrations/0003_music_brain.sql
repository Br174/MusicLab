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
