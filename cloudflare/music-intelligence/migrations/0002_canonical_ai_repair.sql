PRAGMA foreign_keys = ON;

-- LAB12: le decisioni della LAB11 possono essere state memorizzate partendo
-- dal nome del canale/uploader. Le vecchie associazioni non devono diventare
-- verità editoriali permanenti.
ALTER TABLE works ADD COLUMN resolver_version INTEGER NOT NULL DEFAULT 0;

-- Manteniamo le opere come storico, ma invalidiamo i dati derivati dalla
-- precedente pipeline. Verranno ricostruiti dal resolver canonico v12.
DELETE FROM versions;
DELETE FROM playback_bindings;
DELETE FROM research_state;
UPDATE works SET resolver_version = 0;

CREATE INDEX IF NOT EXISTS idx_works_resolver_version ON works(resolver_version);
