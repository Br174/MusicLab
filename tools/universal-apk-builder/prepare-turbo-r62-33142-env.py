#!/usr/bin/env python3
"""R62 signed MusicLab Turbo update: keep package identity and signer."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r61-33141-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33141"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-61-Swipe-Cover-Fluido" in s
s=s.replace("33141","33142")
s=s.replace("Revisione-61-Swipe-Cover-Fluido","Revisione-62-Scorrimento-Autoplay-Universale")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33142"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R62] identity unchanged, signed versionCode=33142")
