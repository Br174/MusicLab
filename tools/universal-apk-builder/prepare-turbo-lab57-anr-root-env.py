#!/usr/bin/env python3
"""LAB57 installer update: same Android family/signing as LAB56, version 33059."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab56-anr-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
updates={
'UAB_FILE_VERSION="TURBO_LAB56"':'UAB_FILE_VERSION="TURBO_LAB57"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB56"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB57"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 56"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 57"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33058"':'UAB_UPDATE_FAMILY_VERSION_CODE="33059"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 56"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 57"',
'export MUSICLAB_VERSION_CODE="33058"':'export MUSICLAB_VERSION_CODE="33059"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab56-anr-stability"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab57-anr-root"',
}
for old,new in updates.items():
    if s.count(old)!=1:raise SystemExit(f"Missing base: {old}")
    s=s.replace(old,new,1)
for required in (
'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in s:raise SystemExit("Identity/signature unexpected: "+required)
p.write_text(s,encoding="utf-8")
print("LAB57 Android: 33059 same signer and package, preserves app data")
