#!/usr/bin/env python3
"""R45 isolated same-family signed APK, no new key or package."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r44-33124-env.py", run_name="__main__")
p=Path("uab-project.env")
old=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33124"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert 'Revisione-44-Standby-Persistente-Universale' in old
updated=old.replace("33124","33125").replace(
    "Revisione-44-Standby-Persistente-Universale",
    "Revisione-45-Versioni-Scroll-Compilation")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33125"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
print("[R45] same signed Turbo identity, versionCode 33125")
