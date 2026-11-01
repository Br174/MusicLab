#!/usr/bin/env python3
"""In-place signed MusicLab R32 update from installed R31 33111."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r31-hitparade-33111-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33111"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-31-Hit-Parade-Epoche" in old
new=old.replace("33111","33112").replace(
    "Revisione-31-Hit-Parade-Epoche","Revisione-32-Hit-Parade-Reale")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33112"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33111" not in new
p.write_text(new)
print("[MUSICLAB R32] in-place update, original package and signature preserved")
