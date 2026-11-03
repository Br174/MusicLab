#!/usr/bin/env python3
"""R53 update in place: restore verified R51 player, correct manual search UI."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r52-33132-env.py",run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33132"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-52-Tracklist-Copertine-Titolo" in old
new=old.replace("33132","33133").replace("Revisione-52-Tracklist-Copertine-Titolo",
  "Revisione-53-Cerca-Titolo-Player-Ripristinato")
p.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33133"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R53] Same app/signature, versionCode 33133, R51 player code restored")
