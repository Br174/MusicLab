#!/usr/bin/env python3
"""R47 signed MusicLab Turbo same family, versionCode 33127."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r46-33126-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33126"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert 'Revisione-46-Compilation-Titolo-Video-Fallback' in old
new=old.replace("33126","33127").replace(
    "Revisione-46-Compilation-Titolo-Video-Fallback",
    "Revisione-47-Hit-Parade-Quattro-Classifiche")
path.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33127"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R47] same signature/applicationId; versionCode 33127")
