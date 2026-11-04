#!/usr/bin/env python3
"""R61 signed update: keep MusicLab Turbo R60 application ID and signer."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r60-33140-env.py", run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33140"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-60-Cover-Compilation-Audio-Rapido" in s
s=s.replace("33140","33141")
s=s.replace("Revisione-60-Cover-Compilation-Audio-Rapido",
            "Revisione-61-Swipe-Cover-Fluido")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33141"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R61] same package/cert, versionCode 33141")
