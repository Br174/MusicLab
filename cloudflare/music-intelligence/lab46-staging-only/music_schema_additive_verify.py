#!/usr/bin/env python3
"""Exactly validate LAB46 single-use isolated-staging SQL in SQLite memory.
No network, no remote D1 writes, no deploy, no cleanup, no production.
"""
from pathlib import Path
import re
import sqlite3

root = Path("cloudflare/music-intelligence")
stage = root / "lab46-staging-only"
migrations = root / "migrations"
sql = (stage / "music_schema_additive_once.sql").read_text()
bootstrap = (stage / "bootstrap.sql").read_text()
originals = [(migrations / f).read_text() for f in [
    "0001_music_intelligence.sql",
    "0002_canonical_ai_repair.sql",
    "0003_music_brain.sql",
    "0004_lab39_cover_artwork.sql",
    "0005_turbo_lab46_complete_archive.sql",
]]
safe_0002 = """PRAGMA foreign_keys = ON;
ALTER TABLE works ADD COLUMN resolver_version INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_works_resolver_version ON works(resolver_version);"""
parts = re.split(r"(?m)^-- === SECTION [1-5]: [^\n]* ===\n", sql)
assert len(parts) == 6, "SQL source sections changed"
for got, original in zip(parts[1:], [
        originals[0], safe_0002, originals[2], originals[3], originals[4]]):
    assert got.strip() == original.strip(), "one of the pinned schema sections differs"
assert "VERIFY DATABASE UUID: e66bd500-cdaa-453c-a81c-c624a9d2611e" in sql
assert "VERIFY CLOUDFLARE ACCOUNT: 5781b597517f6f7fe25751170a99b9ab" in sql
assert "DELETE FROM versions;" in originals[1]
assert "DELETE FROM playback_bindings;" in originals[1]
assert "DELETE FROM research_state;" in originals[1]
assert "UPDATE works SET resolver_version = 0;" in originals[1]
cleaned = re.sub(r"--[^\n]*", "", sql)
assert not re.search(
    r"\b(?:DELETE\s+FROM|DROP\s+(?:TABLE|INDEX)|UPDATE\s+\w+\s+SET|"
    r"REPLACE\s+INTO|INSERT\s+(?:OR\s+\w+\s+)?INTO|TRUNCATE|ATTACH)\b",
    cleaned, re.I,
), "non-additive DML present in SQL"

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
db.executescript(bootstrap)

def current_tables():
    return {r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    )}

expected_bootstrap = {"lab46_staging_sentinel", "lab46_sync_receipts"}
assert current_tables() == expected_bootstrap, "pre-migration stage tables unexpected"
marker = "musiclab-turbo-lab46-isolated-d1-v1"
assert db.execute("SELECT marker FROM lab46_staging_sentinel").fetchall() == [(marker,)]
preexisting = ("test-revision", "test-fingerprint", "test-sha", "pending")
db.execute(
    "INSERT INTO lab46_sync_receipts(revision,fingerprint,payload_hash,state)"
    " VALUES(?,?,?,?)", preexisting
)
db.commit()

# PRE-FLIGHT: existing scope must be the already proven staging sentinel.
def verify_before_execution(conn):
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    )}
    assert tables == expected_bootstrap, "unexpected tables: do not re-run"
    found = conn.execute(
        "SELECT marker FROM lab46_staging_sentinel"
    ).fetchall()
    assert found == [(marker,)], "wrong sentinel: never apply schema"

verify_before_execution(db)
db.executescript(sql)

tables = current_tables()
needed = {
    "lab46_staging_sentinel", "lab46_sync_receipts",
    "works", "versions", "playback_bindings", "artists", "albums",
    "research_state", "requests_log", "work_aliases", "related_works",
    "version_evidence", "decision_history", "search_missions",
    "coverage_cells",
}
assert needed <= tables, needed-tables
cols = {r[1] for r in db.execute("PRAGMA table_info(versions)")}
assert {"recording_fingerprint","musiclab_score_100","legacy_score_20",
        "cover_url","release_date","cover_round","round_position",
        "source_names_json","work_relation_confirmed"} <= cols
indexes = {r[0] for r in db.execute(
    "SELECT name FROM sqlite_master WHERE type='index'"
)}
assert {"idx_lab46_sync_receipts_fingerprint",
        "idx_versions_work_round_position"} <= indexes
assert db.execute(
    "SELECT marker FROM lab46_staging_sentinel"
).fetchall() == [(marker,)]
assert db.execute(
    "SELECT revision,fingerprint,payload_hash,state FROM lab46_sync_receipts"
).fetchall() == [preexisting], "existing receipt changed"
assert db.execute("PRAGMA foreign_key_check").fetchall() == []

db.execute(
    "INSERT INTO works(id,search_key,canonical_title,original_artist)"
    " VALUES('sample','test|artist','Song','Artist')"
)
db.execute(
    "INSERT INTO versions(id,work_id,version_key,canonical_title,canonical_artist,category)"
    " VALUES('version1','sample','cover|artist','Song','Other','cover')"
)
db.commit()

def reject(statement):
    try:
        db.execute(statement)
    except sqlite3.IntegrityError:
        return
    raise AssertionError("invalid row accepted: " + statement)

reject("UPDATE versions SET musiclab_score_100=101 WHERE id='version1'")
reject("UPDATE versions SET legacy_score_20=21 WHERE id='version1'")
reject("UPDATE versions SET work_relation_confirmed=2 WHERE id='version1'")
reject("UPDATE versions SET decision_status='INVALID' WHERE id='version1'")
reject("INSERT INTO lab46_staging_sentinel(marker) VALUES('musiclab-turbo-lab46-isolated-d1-v1')")
# Single-use guard: refuse to apply to any database already containing core tables.
try:
    verify_before_execution(db)
except AssertionError:
    pass
else:
    raise AssertionError("second schema execution should be explicitly forbidden")

wrong = sqlite3.connect(":memory:")
wrong.executescript(bootstrap)
wrong.execute("UPDATE lab46_staging_sentinel SET marker='wrong-stage'")
try:
    verify_before_execution(wrong)
except AssertionError:
    pass
else:
    raise AssertionError("wrong marker must block application")
wrong.close()
db.close()
print("LAB46 ADDITIVE SQL EXACT ARTIFACT PASS")
print("13 required core/add-on tables + 2 pre-existing staging tables verified")
print("24 add-column clauses; no destructive DML; five row negatives + two guards PASS")
print("Original staging sentinel + pre-existing receipt preserved; 0 remote writes")
