#!/usr/bin/env python3
"""MusicLab Turbo R38 33118: preserve signed family 33117 exactly."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r37-actions-33117-env.py", run_name="__main__")
env = Path("uab-project.env")
old = env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33117"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-37-Menu-Compilation" in old
new = old.replace("33117", "33118").replace(
    "Revisione-37-Menu-Compilation", "Revisione-38-Compilation-Sessioni-Cronologia"
)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33118"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
env.write_text(new)
print("[MUSICLAB 33118] R38 isolated Compilation sessions, native family/signing unchanged")
