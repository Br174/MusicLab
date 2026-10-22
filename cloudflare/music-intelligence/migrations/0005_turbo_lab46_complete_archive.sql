-- MusicLab Turbo LAB46, additive compatibility migration (DO NOT apply to shared D1).
-- A staging-only dry-run must precede any explicitly authorized remote migration.
-- NULL means unverified/unavailable; never fabricate missing metadata.
ALTER TABLE versions ADD COLUMN release_date TEXT;
ALTER TABLE versions ADD COLUMN country TEXT;
ALTER TABLE versions ADD COLUMN source_names_json TEXT;
ALTER TABLE versions ADD COLUMN source_url TEXT;
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
