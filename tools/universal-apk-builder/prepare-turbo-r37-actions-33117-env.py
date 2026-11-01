#!/usr/bin/env python3
"""MusicLab Turbo 33117: preserve the R36 APK update family and signing identity."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r36-fastpath-33116-env.py",run_name="__main__")
env=Path("uab-project.env")
old=env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33116"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-36-FastPath-HitParade-Compilation" in old
new=old.replace("33116","33117").replace("Revisione-36-FastPath-HitParade-Compilation","Revisione-37-Menu-Compilation")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33117"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
env.write_text(new)
print("[MUSICLAB 33117] Same APK family + signer, new R37 menu and compilation search")
