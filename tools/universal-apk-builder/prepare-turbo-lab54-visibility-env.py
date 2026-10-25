#!/usr/bin/env python3
"""LAB54: same install identity and signing certificate as LAB53; code 33056."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab53-50-cover-env.py",run_name="__main__")
path=Path("uab-project.env")
source=path.read_text(encoding="utf-8")
updates={
'UAB_FILE_VERSION="TURBO_LAB53"':'UAB_FILE_VERSION="TURBO_LAB54"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB53"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB54"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 53"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 54"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33055"':'UAB_UPDATE_FAMILY_VERSION_CODE="33056"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 53"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 54"',
'export MUSICLAB_VERSION_CODE="33055"':'export MUSICLAB_VERSION_CODE="33056"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab53-cover50"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab54-visible50"',
}
for old,new in updates.items():
    if source.count(old)!=1:raise SystemExit(f"Unexpected prior install family: {old}")
    source=source.replace(old,new,1)
for value in (
'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if value not in source:raise SystemExit(f"Install family or signing mismatch: {value}")
path.write_text(source,encoding="utf-8")
print("LAB54: versionCode 33056, unchanged application ID and cert")
