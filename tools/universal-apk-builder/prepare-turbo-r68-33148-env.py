#!/usr/bin/env python3
"""R68 visual-only Compilation subtitle update; preserve signed R67 identity."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r67-33147-env.py", run_name="__main__")
path = Path("uab-project.env")
env = path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33147"' in env
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in env
assert "Revisione-67-Audio-First-Stabile" in env
env = env.replace("33147", "33148")
env = env.replace("Revisione-67-Audio-First-Stabile",
                  "Revisione-68-Compilation-Nomi-Puliti")
path.write_text(env)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33148"' in env
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in env
print("[R68] Signed Turbo identity retained, versionCode=33148")
