#!/usr/bin/env python3
"""LAB58: retain MusicLab Turbo signed update identity; increment version only."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r57-33137-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33137"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
s = s.replace("33137", "33138")
s = s.replace("Revisione-57-Compilation-Risultati-In-Blocco",
              "Revisione-58-Copertine-Leggere")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33138"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[LAB58] Same signed Turbo update family, versionCode 33138")
