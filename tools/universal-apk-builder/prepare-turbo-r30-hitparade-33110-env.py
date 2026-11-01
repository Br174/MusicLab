#!/usr/bin/env python3
"""Prepare same signed Android family: 33109 -> 33110, parser-only fix."""
import runpy
from pathlib import Path

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r29-hitparade-33109-env.py", run_name="__main__")
path = Path("uab-project.env")
old = path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33109"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-29-Hit-Parade" in old
new = old.replace('UAB_UPDATE_FAMILY_VERSION_CODE="33109"', 'UAB_UPDATE_FAMILY_VERSION_CODE="33110"')
new = new.replace("Revisione-29-Hit-Parade", "Revisione-30-Hit-Parade-Correzione")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33110"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
path.write_text(new)
print("[MUSICLAB R30 33110] In-place 33109 update; existing signature and player preserved")
