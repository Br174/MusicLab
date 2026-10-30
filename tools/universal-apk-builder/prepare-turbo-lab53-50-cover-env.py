#!/usr/bin/env python3
"""LAB53: same Turbo signature, package and installer, versionCode 33055."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab52-multiproof-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
changes={
'UAB_FILE_VERSION="TURBO_LAB52"':'UAB_FILE_VERSION="TURBO_LAB53"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB52"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB53"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 52"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 53"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33054"':'UAB_UPDATE_FAMILY_VERSION_CODE="33055"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 52"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 53"',
'export MUSICLAB_VERSION_CODE="33054"':'export MUSICLAB_VERSION_CODE="33055"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab52-multiproof"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab53-cover50"',
}
for old,new in changes.items():
    if s.count(old)!=1: raise SystemExit(f"LAB53 source mismatch: {old}")
    s=s.replace(old,new,1)
for expected in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if expected not in s:raise SystemExit("LAB53 signing family unexpectedly changed")
p.write_text(s,encoding="utf-8")
print("LAB53: installer it.verlezza.musiclab.turbo01 code 33055 signer unchanged")
