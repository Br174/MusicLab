#!/usr/bin/env python3
"""R58 signed update: preserve verified R57 package identity and certificate."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r57-33137-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33137"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-57-Compilation-Risultati-In-Blocco" in s
s = s.replace("33137", "33138")
s = s.replace("Revisione-57-Compilation-Risultati-In-Blocco",
              "Revisione-58-Audio-Immediato-Globale")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33138"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R58] preserved package/signing; versionCode 33138")
