#!/usr/bin/env python3
"""MusicLab R59 signed update: preserve exact R58 package/signature family."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r58-33138-env.py", run_name="__main__")
env = Path("uab-project.env")
data = env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33138"' in data
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in data
assert "Revisione-58-Audio-Immediato-Globale" in data
data = data.replace("33138", "33139")
data = data.replace("Revisione-58-Audio-Immediato-Globale",
                    "Revisione-59-Player-Globale-Anti-Riavvio")
env.write_text(data)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33139"' in data
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in data
print("[R59] signed MusicLab Turbo identity unchanged; versionCode 33139")
