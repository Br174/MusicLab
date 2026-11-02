#!/usr/bin/env python3
"""MusicLab Turbo R39: preserve R38 signed update family, versionCode 33119."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r38-session-33118-env.py", run_name="__main__")
env = Path("uab-project.env")
old = env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33118"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-38-Compilation-Sessioni-Cronologia" in old
new = old.replace("33118", "33119").replace(
    "Revisione-38-Compilation-Sessioni-Cronologia",
    "Revisione-39-Ricerca-Originale-Compilation"
)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33119"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
env.write_text(new)
print("[MUSICLAB 33119] R36 original Compilation search restored; R38 family and signing retained")
