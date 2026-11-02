#!/usr/bin/env python3
"""MusicLab 33120 preserves R39 signed update-family identity."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r39-legacy-search-33119-env.py",run_name="__main__")
env=Path("uab-project.env")
source=env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33119"' in source
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert 'Revisione-39-Ricerca-Originale-Compilation' in source
result=source.replace("33119","33120").replace(
    "Revisione-39-Ricerca-Originale-Compilation",
    "Revisione-40-Contesto-Playlist-Scudo"
)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33120"' in result
env.write_text(result)
print("[R40] signed Turbo 33120 derived from user-accepted R39")
