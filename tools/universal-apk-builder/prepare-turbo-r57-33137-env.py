#!/usr/bin/env python3
"""R57: same signed MusicLab Turbo update family as verified R56."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r56-33136-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33136"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
s = s.replace("33136", "33137")
s = s.replace("Revisione-56-Player-Audio-Globale-Stabile",
              "Revisione-57-Compilation-Risultati-In-Blocco")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33137"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R57] same app identity and signer, versionCode 33137")
