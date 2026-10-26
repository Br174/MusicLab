#!/usr/bin/env python3
"""LAB71: same-family signed upgrade of verified LAB70; no package/cert change."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab70-first-round-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
replacements={
  'UAB_FILE_VERSION="TURBO_LAB70"':'UAB_FILE_VERSION="TURBO_LAB71"',
  'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB70"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB71"',
  'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 70"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 71"',
  'UAB_UPDATE_FAMILY_VERSION_CODE="33072"':'UAB_UPDATE_FAMILY_VERSION_CODE="33073"',
  'export METROLIST_APP_NAME="MusicLab Turbo LAB 70"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 71"',
  'export MUSICLAB_VERSION_CODE="33072"':'export MUSICLAB_VERSION_CODE="33073"',
  'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab70-first-round"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab71-cover-foundations"',
}
for old,new in replacements.items():
    if s.count(old)!=1:
        raise SystemExit("Unexpected LAB70 upgrade baseline: "+old)
    s=s.replace(old,new,1)
for needed in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if needed not in s:
        raise SystemExit("Signing or application identity altered")
p.write_text(s,encoding="utf-8")
print("LAB71 update-in-place family verified; versionCode 33073")
