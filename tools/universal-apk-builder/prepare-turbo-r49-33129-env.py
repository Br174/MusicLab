#!/usr/bin/env python3
"""MusicLab R49 same-family update signed as 33129; no signing rotation."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r48-33128-env.py",run_name="__main__")
file=Path("uab-project.env")
old=file.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33128"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-48-Hit-Parade-Video-Recuperati" in old
new=old.replace("33128", "33129").replace(
    "Revisione-48-Hit-Parade-Video-Recuperati",
    "Revisione-49-Hit-Parade-Titolo-Data")
file.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33129"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R49] Installed Turbo signing/app family preserved; versionCode 33129")
