#!/usr/bin/env python3
"""R64: retain signed R63 Turbo update identity, bump code 33144 only."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r63-33143-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33143"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-63-Swipe-Reattivo-Globale" in s
s = s.replace("33143", "33144")
s = s.replace("Revisione-63-Swipe-Reattivo-Globale",
              "Revisione-64-Copertine-Smart-Leggere")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33144"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R64] Same app signature and package, versionCode 33144")
