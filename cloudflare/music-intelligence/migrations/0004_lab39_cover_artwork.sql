PRAGMA foreign_keys = ON;

-- LAB39: persist artwork for approved cloud versions.
-- Playback ids already use the playback_bindings table created in 0001.
ALTER TABLE versions ADD COLUMN cover_url TEXT;
