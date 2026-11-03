#!/usr/bin/env python3
"""R54: signed MusicLab Turbo update. Restore R53 player unchanged."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r53-33133-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33133"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
s=s.replace("33133","33134")
s=s.replace("Revisione-53-Cerca-Titolo-Player-Ripristinato",
            "Revisione-54-Discogs-Tre-Ricerche-Cover-Leggera")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33134"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R54] same installer signature + app ID; versionCode 33134")
