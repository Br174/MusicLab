#!/usr/bin/env python3
"""R46 same signed MusicLab Turbo app id and certificate, versionCode 33126."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r45-33125-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33125"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert 'Revisione-45-Versioni-Scroll-Compilation' in old
updated=old.replace("33125","33126").replace(
    "Revisione-45-Versioni-Scroll-Compilation",
    "Revisione-46-Compilation-Titolo-Video-Fallback")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33126"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
print("[R46] original MusicLab Turbo signed update identity preserved, version 33126")
