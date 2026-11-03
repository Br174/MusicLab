#!/usr/bin/env python3
"""R56 signed update from verified R55 without changing installer family."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r55-33135-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33135"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
s=s.replace("33135","33136")
s=s.replace("Revisione-55-Discogs-Multiselezione-Ricerca-Veloce",
            "Revisione-56-Player-Audio-Globale-Stabile")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33136"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R56] signature and package identity preserved, versionCode 33136")
