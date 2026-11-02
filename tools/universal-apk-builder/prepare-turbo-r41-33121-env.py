#!/usr/bin/env python3
"""R41 same certificate and package, versionCode 33121."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r40-33120-env.py",run_name="__main__")
env=Path("uab-project.env")
old=env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33120"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-40-Contesto-Playlist-Scudo" in old
new=old.replace("33120","33121").replace(
    "Revisione-40-Contesto-Playlist-Scudo",
    "Revisione-41-Memoria-Persistente-Universale")
env.write_text(new)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33121"' in new
print("[R41] signed Android family unchanged; universal section-memory LAB version 33121")
