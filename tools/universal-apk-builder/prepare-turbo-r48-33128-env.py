#!/usr/bin/env python3
"""MusicLab R48: same installed Turbo identity, signing and exact next versionCode."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r47-33127-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33127"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-47-Hit-Parade-Quattro-Classifiche" in old
new=old.replace("33127", "33128").replace(
    "Revisione-47-Hit-Parade-Quattro-Classifiche",
    "Revisione-48-Hit-Parade-Video-Recuperati")
path.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33128"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R48] same family/signing; versionCode 33128")
