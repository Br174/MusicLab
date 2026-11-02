#!/usr/bin/env python3
"""R42 same signed Turbo application, versionCode 33122."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r41-33121-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33121"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-41-Memoria-Persistente-Universale" in old
updated=old.replace("33121","33122").replace(
    "Revisione-41-Memoria-Persistente-Universale",
    "Revisione-42-Player-Seek-Selezione-Sync")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33122"' in updated
print("[R42] same signed package as R41, new isolated build 33122")
