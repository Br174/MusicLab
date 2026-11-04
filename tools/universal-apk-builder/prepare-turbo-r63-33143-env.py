#!/usr/bin/env python3
"""R63 signed MusicLab Turbo update, keep R62 package/signing identity."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r62-33142-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33142"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-62-Scorrimento-Autoplay-Universale" in s
s=s.replace("33142","33143")
s=s.replace("Revisione-62-Scorrimento-Autoplay-Universale",
            "Revisione-63-Swipe-Reattivo-Globale")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33143"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R63] package unchanged, signed update versionCode=33143")
