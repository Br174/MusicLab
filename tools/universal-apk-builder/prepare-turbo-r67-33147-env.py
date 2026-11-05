#!/usr/bin/env python3
"""R67: keep user-installed MusicLab Turbo signature and package."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r66-33146-env.py", run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33146"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-66-Unicode-HitParade-Cover" in s
s=s.replace("33146","33147")
s=s.replace("Revisione-66-Unicode-HitParade-Cover",
            "Revisione-67-Audio-First-Stabile")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33147"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R67] Signed Turbo 33147, original signature/package preserved")
