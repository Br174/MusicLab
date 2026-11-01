#!/usr/bin/env python3
"""MusicLab LAB 33114 Player Fluido: signed in-place upgrade from verified 33113."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r33-hitparade-33113-env.py",run_name="__main__")
env = Path("uab-project.env")
old = env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33113"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-33-Hit-Parade-HTML-Reale" in old
new = old.replace("33113", "33114").replace(
    "Revisione-33-Hit-Parade-HTML-Reale", "Revisione-34-Player-Fluido")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33114"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33113" not in new
env.write_text(new)
print("[MUSICLAB R34] Same app ID/signature; versionCode 33114; MusicLab R33 source immutable")
