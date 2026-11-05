#!/usr/bin/env python3
"""R69: keep signed R68 update family while removing only Compilation text."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r68-33148-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33148"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-68-Compilation-Nomi-Puliti" in s
s=s.replace("33148","33149")
s=s.replace("Revisione-68-Compilation-Nomi-Puliti","Revisione-69-Compilation-Sottotitolo-Pulito")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33149"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R69] Original signed Turbo update family, versionCode 33149")
