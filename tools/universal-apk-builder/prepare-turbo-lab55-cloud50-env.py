#!/usr/bin/env python3
"""LAB55 update-in-place over LAB54. No paid services or signature changes."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab54-visibility-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
replacements={
'UAB_FILE_VERSION="TURBO_LAB54"':'UAB_FILE_VERSION="TURBO_LAB55"',
'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB54"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB55"',
'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 54"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 55"',
'UAB_UPDATE_FAMILY_VERSION_CODE="33056"':'UAB_UPDATE_FAMILY_VERSION_CODE="33057"',
'export METROLIST_APP_NAME="MusicLab Turbo LAB 54"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 55"',
'export MUSICLAB_VERSION_CODE="33056"':'export MUSICLAB_VERSION_CODE="33057"',
'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab54-visible50"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab55-cloud50"',
}
for old,new in replacements.items():
    if s.count(old)!=1:raise SystemExit(f"Existing update family mismatch: {old}")
    s=s.replace(old,new,1)
for value in (
'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'
):
    if value not in s:raise SystemExit(f"Changed signer or app identity: {value}")
p.write_text(s,encoding="utf-8")
print("LAB55 identity: it.verlezza.musiclab.turbo01; version 33057; same signing cert")
