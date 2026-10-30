#!/usr/bin/env python3
"""LAB56: fix Android ANR without reverting the 50-cover Cloud pipeline."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab55-cloud50-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
changes={
'UAB_FILE_VERSION="TURBO_LAB55"':'UAB_FILE_VERSION="TURBO_LAB56"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB55"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB56"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 55"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 56"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33057"':'UAB_UPDATE_FAMILY_VERSION_CODE="33058"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 55"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 56"',
'export MUSICLAB_VERSION_CODE="33057"':'export MUSICLAB_VERSION_CODE="33058"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab55-cloud50"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab56-anr-stability"',
}
for old,new in changes.items():
    if s.count(old)!=1:raise SystemExit(f"Prior update family not present: {old}")
    s=s.replace(old,new,1)
for required in (
'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in s:raise SystemExit("LAB56 cannot preserve installer identity")
p.write_text(s,encoding="utf-8")
print("LAB56: version 33058, exact package and signing cert retained")
