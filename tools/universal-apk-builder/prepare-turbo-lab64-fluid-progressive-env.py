#!/usr/bin/env python3
"""MusicLab Turbo LAB64 same-signature Android update, versionCode 33066."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab63-anr-round-identity-env.py", run_name="__main__")
path=Path("uab-project.env")
text=path.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB63"':'UAB_FILE_VERSION="TURBO_LAB64"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB63"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB64"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 63"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 64"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33065"':'UAB_UPDATE_FAMILY_VERSION_CODE="33066"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 63"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 64"',
 'export MUSICLAB_VERSION_CODE="33065"':'export MUSICLAB_VERSION_CODE="33066"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab63-anr-round-identity"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab64-fluid-progressive"',
}
for old,new in changes.items():
    if text.count(old)!=1:
        raise SystemExit("LAB63 baseline changed: "+old)
    text=text.replace(old,new,1)
for expected in ('UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'):
    if expected not in text:
        raise SystemExit("Android identity drift: "+expected)
path.write_text(text,encoding="utf-8")
print("LAB64 versionCode=33066 and same-family Android signature")
