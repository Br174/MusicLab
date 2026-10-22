#!/usr/bin/env python3
"""LAB46 staging-only SQL migration test. Never connects to remote Cloudflare."""
from pathlib import Path
import sqlite3
import json

root = Path("cloudflare/music-intelligence/migrations")
con = sqlite3.connect(":memory:")
for name in ["0001_music_intelligence.sql", "0002_canonical_ai_repair.sql",
             "0003_music_brain.sql", "0004_lab39_cover_artwork.sql"]:
    con.executescript((root / name).read_text())
con.execute("INSERT INTO works(id,search_key,canonical_title,original_artist) VALUES(?,?,?,?)",
            ("work-1", "track|artist", "Track", "Artist"))
con.execute("""INSERT INTO versions
(id,work_id,version_key,canonical_title,canonical_artist,category)
VALUES(?,?,?,?,?,?)""", ("version-1", "work-1", "recording-1", "Track", "Other", "cover"))
con.commit()
con.executescript((root / "0005_turbo_lab46_complete_archive.sql").read_text())
cols = {row[1] for row in con.execute("PRAGMA table_info(versions)")}
required = {"release_date","country","source_names_json","source_url","credits_detail_json","video_title",
            "recording_fingerprint","legacy_score_20","musiclab_score_100","cover_round",
            "round_position","work_relation_confirmed"}
assert required <= cols, required - cols
con.execute("""UPDATE versions SET release_date=?, country=?, source_names_json=?,
 video_title=?, legacy_score_20=?, musiclab_score_100=?,
 cover_round=?, round_position=? WHERE id=?""",
 ("2026-10-22", "IT", json.dumps(["MusicBrainz", "Discogs"]),
  "Verified song", 16, 84, 2, 73, "version-1"))
saved = con.execute("""SELECT canonical_title, release_date, country,
 legacy_score_20,musiclab_score_100,cover_round,round_position FROM versions""").fetchone()
assert saved == ("Track", "2026-10-22", "IT", 16, 84, 2, 73), saved
try:
    con.execute("UPDATE versions SET musiclab_score_100=120 WHERE id='version-1'")
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("Rejected impossible >100 score not enforced")
assert con.execute("SELECT COUNT(*) FROM versions").fetchone()[0] == 1
print("LAB46 SQL smoke PASS: historical row preserved, complete fields, 0..100 bounds")
