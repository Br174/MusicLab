#!/usr/bin/env python3
"""R51 signed MusicLab update family; keep same package and signing identity."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r50-33130-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33130"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-50-Hit-Parade-Copertine-Vere" in old
new=old.replace("33130","33131").replace(
    "Revisione-50-Hit-Parade-Copertine-Vere",
    "Revisione-51-Compilation-Album-Categorie")
path.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33131"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R51] Same family/signing; versionCode 33131")
