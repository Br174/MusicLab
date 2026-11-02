#!/usr/bin/env python3
"""R43 same signed MusicLab Turbo, versionCode 33123, no package change."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r42-33122-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33122"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-42-Player-Seek-Selezione-Sync" in old
updated=old.replace("33122","33123").replace(
    "Revisione-42-Player-Seek-Selezione-Sync",
    "Revisione-43-Compilation-FastPath-Automatico")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33123"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
print("[R43] same signed package as verified R42; 33123 isolated update")
