#!/usr/bin/env python3
"""R55 signed update: preserve R54 package identity and certificate."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r54-33134-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33134"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
s=s.replace("33134","33135")
s=s.replace("Revisione-54-Discogs-Tre-Ricerche-Cover-Leggera",
            "Revisione-55-Discogs-Multiselezione-Ricerca-Veloce")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33135"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R55] same package & signing, new versionCode 33135")
