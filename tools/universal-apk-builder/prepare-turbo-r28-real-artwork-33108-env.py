#!/usr/bin/env python3
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r27-artwork-33107-env.py",run_name="__main__")
path=Path("uab-project.env")
before=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33107"' in before
assert "Revisione-27-Copertine-Ricerca" in before
new=before.replace("33107","33108").replace(
    "Revisione-27-Copertine-Ricerca","Revisione-28-Classifiche-Copertine-Reali")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33108"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
path.write_text(new)
print("[MUSICLAB 33108] In-place APK update signed as 33107")
