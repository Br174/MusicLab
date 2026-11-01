#!/usr/bin/env python3
"""MusicLab R31 Hit Parade parser correction, same signed Turbo family."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r30-hitparade-33110-env.py", run_name="__main__")
p = Path("uab-project.env")
old = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33110"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-30-Hit-Parade-Correzione" in old
new = old.replace("33110", "33111").replace(
    "Revisione-30-Hit-Parade-Correzione", "Revisione-31-Hit-Parade-Epoche")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33111"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33110" not in new
p.write_text(new)
print("[MUSICLAB R31] versionCode 33111, same Android identity and signer")
