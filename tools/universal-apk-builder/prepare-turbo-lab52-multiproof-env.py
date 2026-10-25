#!/usr/bin/env python3
"""LAB52: immutable LAB51 update family, versionCode 33054, same signer."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab51-cover-breadth-env.py", run_name="__main__")
path=Path("uab-project.env")
src=path.read_text(encoding="utf-8")
changes={
'UAB_FILE_VERSION="TURBO_LAB51"':'UAB_FILE_VERSION="TURBO_LAB52"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB51"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB52"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 51"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 52"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33053"':'UAB_UPDATE_FAMILY_VERSION_CODE="33054"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 51"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 52"',
'export MUSICLAB_VERSION_CODE="33053"':'export MUSICLAB_VERSION_CODE="33054"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab51-cover-breadth"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab52-multiproof"',
}
for old,new in changes.items():
    if src.count(old)!=1:
        raise SystemExit(f"LAB52 incompatible source stamp: {old}")
    src=src.replace(old,new,1)
for required in (
'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in src: raise SystemExit(f"LAB52 package/signature mismatch: {required}")
path.write_text(src,encoding="utf-8")
print("LAB52 update-in-place: versionCode 33054, signer & applicationId untouched")
