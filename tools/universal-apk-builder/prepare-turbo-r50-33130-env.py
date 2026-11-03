#!/usr/bin/env python3
"""R50 signed MusicLab installed family 33130, no signing or package change."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r49-33129-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33129"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-49-Hit-Parade-Titolo-Data" in old
new=old.replace("33129", "33130").replace(
    "Revisione-49-Hit-Parade-Titolo-Data",
    "Revisione-50-Hit-Parade-Copertine-Vere")
path.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33130"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R50] same installed APK family; versionCode 33130")
