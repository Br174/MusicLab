#!/usr/bin/env python3
"""R44 same signed MusicLab Turbo, versionCode 33124, no package change."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r43-33123-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33123"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-43-Compilation-FastPath-Automatico" in old
updated=old.replace("33123","33124").replace(
    "Revisione-43-Compilation-FastPath-Automatico",
    "Revisione-44-Standby-Persistente-Universale")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33124"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
print("[R44] same signed Turbo package and cert as verified R43; 33124 isolated update")
