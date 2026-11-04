#!/usr/bin/env python3
"""R60: identical signed MusicLab Turbo update family as proven R59."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r59-33139-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33139"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-59-Player-Globale-Anti-Riavvio" in s
s = s.replace("33139","33140")
s = s.replace("Revisione-59-Player-Globale-Anti-Riavvio",
              "Revisione-60-Cover-Compilation-Audio-Rapido")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33140"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R60] same package and signer; new versionCode 33140")
