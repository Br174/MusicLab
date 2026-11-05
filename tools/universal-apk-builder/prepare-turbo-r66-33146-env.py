#!/usr/bin/env python3
"""R66: same signed MusicLab Turbo update identity as successful R65."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r65-33145-env.py", run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33145"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-65-Filtri-Cover-Date-Certificate" in s
s=s.replace("33145", "33146")
s=s.replace("Revisione-65-Filtri-Cover-Date-Certificate",
            "Revisione-66-Unicode-HitParade-Cover")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33146"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R66] Signed Turbo family unchanged, versionCode=33146")
