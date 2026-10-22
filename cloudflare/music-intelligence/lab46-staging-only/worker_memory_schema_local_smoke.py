#!/usr/bin/env python3
"""LAB46 Worker memory-schema preparation, SQLite :memory: only.
Never connects to Cloudflare or executes SQL against a remote D1.
This does NOT authorize remote migration or Worker deployment.
"""
import sqlite3
from pathlib import Path

ROOT = Path("cloudflare/music-intelligence")
MIG = ROOT / "migrations"
BOOT = ROOT / "lab46-staging-only/bootstrap.sql"
STAGING_SENTINEL = "musiclab-turbo-lab46-isolated-d1-v1"

def sql(name):
    return (MIG / name).read_text(encoding="utf-8")

base = sql("0001_music_intelligence.sql")
repair = sql("0002_canonical_ai_repair.sql")
brain = sql("0003_music_brain.sql")
cover = sql("0004_lab39_cover_artwork.sql")
lab46 = sql("0005_turbo_lab46_complete_archive.sql")
bootstrap = BOOT.read_text(encoding="utf-8")

# Historical 0002 deletes versions, playback and research state and resets
# legacy works. The actual file is not modified; unsafe operations are NOT
# imported into this proposed isolated-staging-only schema plan.
unsafe = (
    "DELETE FROM versions;",
    "DELETE FROM playback_bindings;",
    "DELETE FROM research_state;",
    "UPDATE works SET resolver_version = 0;",
)
assert all(repair.count(stmt) == 1 for stmt in unsafe), "upstream repair changed; manual review needed"
safe_repair = """PRAGMA foreign_keys = ON;
ALTER TABLE works ADD COLUMN resolver_version INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_works_resolver_version ON works(resolver_version);
"""
assert "ALTER TABLE works ADD COLUMN resolver_version INTEGER NOT NULL DEFAULT 0;" in repair
assert "CREATE INDEX IF NOT EXISTS idx_works_resolver_version ON works(resolver_version);" in repair

connection = sqlite3.connect(":memory:")
connection.execute("PRAGMA foreign_keys = ON")
connection.executescript(bootstrap)
connection.executescript(base)
connection.execute(
    "INSERT INTO works(id,search_key,canonical_title,original_artist)"
    " VALUES(?,?,?,?)",
    ("work-1", "work|artist", "Work", "Artist"),
)
connection.execute(
    "INSERT INTO versions(id,work_id,version_key,canonical_title,canonical_artist,category)"
    " VALUES(?,?,?,?,?,?)",
    ("version-1", "work-1", "version|artist", "Work", "Other Artist", "cover"),
)
connection.commit()
connection.executescript(safe_repair)
for migration in (brain, cover, lab46):
    connection.executescript(migration)

tables = {item[0] for item in connection.execute(
    "SELECT name FROM sqlite_master WHERE type='table'"
)}
required_tables = {
    "works", "versions", "playback_bindings", "research_state",
    "lab46_staging_sentinel", "lab46_sync_receipts",
    "work_aliases", "version_evidence", "decision_history",
    "search_missions", "coverage_cells",
}
assert required_tables <= tables, required_tables - tables
indices = {item[0] for item in connection.execute(
    "SELECT name FROM sqlite_master WHERE type='index'"
)}
assert {"idx_lab46_sync_receipts_fingerprint",
        "idx_versions_work_round_position"} <= indices
assert connection.execute(
    "SELECT marker FROM lab46_staging_sentinel"
).fetchall() == [(STAGING_SENTINEL,)]
assert connection.execute(
    "SELECT COUNT(*) FROM lab46_sync_receipts"
).fetchone()[0] == 0
assert connection.execute("SELECT COUNT(*) FROM works").fetchone()[0] == 1
assert connection.execute("SELECT COUNT(*) FROM versions").fetchone()[0] == 1
assert connection.execute(
    "SELECT resolver_version FROM works WHERE id='work-1'"
).fetchone() == (0,)

negative_cases = 0
def must_reject(statement, values=()):
    global negative_cases
    try:
        connection.execute(statement, values)
    except sqlite3.IntegrityError:
        negative_cases += 1
        return
    raise AssertionError("Invalid state unexpectedly accepted: " + statement[:100])

must_reject(
    "INSERT INTO lab46_staging_sentinel(marker) VALUES(?)", (STAGING_SENTINEL,)
)
must_reject(
    "INSERT INTO lab46_sync_receipts(revision,fingerprint,payload_hash,state)"
    " VALUES(?,?,?,?)", ("rev-a", "fingerprint", "hash", "unknown")
)
must_reject(
    "UPDATE versions SET musiclab_score_100=101 WHERE id='version-1'"
)
must_reject(
    "UPDATE versions SET legacy_score_20=21 WHERE id='version-1'"
)
assert negative_cases == 4
assert connection.execute(
    "SELECT COUNT(*) FROM versions WHERE id='version-1'"
).fetchone()[0] == 1
print("LAB46_STAGING_MEMORY_SCHEMA_PREP PASS")
print("Local positive: 1; negative: 4; upstream destructive reset statements excluded: 4")
print("Staging sentinel present, receipts empty, baseline memory tables and scores verified")
print("REMOTE_SCHEMA_APPLIED=false; DEPLOYED=false; REMOTE_WRITES=0")
connection.close()
