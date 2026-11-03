#!/usr/bin/env python3
"""R52 same signed Android MusicLab installed family, incremental version 33132."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r51-33131-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33131"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-51-Compilation-Album-Categorie" in old
new=old.replace("33131","33132").replace(
    "Revisione-51-Compilation-Album-Categorie",
    "Revisione-52-Tracklist-Copertine-Titolo")
path.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33132"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
print("[R52] preserved installed MusicLab family; versionCode=33132")
