#!/usr/bin/env python3
"""LAB75: EXACT LAB71 CoverLab runtime with a higher signed Android version code.
No CoverLab source, player, queries, admission, or persisted-round format changes.
"""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab71-foundations-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
changes={
  'UAB_FILE_VERSION="TURBO_LAB71"':'UAB_FILE_VERSION="TURBO_LAB75"',
  'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB71"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB75"',
  'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 71"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 75"',
  'UAB_UPDATE_FAMILY_VERSION_CODE="33073"':'UAB_UPDATE_FAMILY_VERSION_CODE="33077"',
  'export METROLIST_APP_NAME="MusicLab Turbo LAB 71"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 75"',
  'export MUSICLAB_VERSION_CODE="33073"':'export MUSICLAB_VERSION_CODE="33077"',
  'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab71-cover-foundations"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab75-exact-lab71-restore"',
}
for old,new in changes.items():
    if s.count(old)!=1: raise SystemExit("Unexpected LAB71 baseline: "+old)
    s=s.replace(old,new,1)
for immutable in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'
):
    if immutable not in s: raise SystemExit("Package/signature mismatch")
p.write_text(s,encoding="utf-8")
print("LAB75 exact LAB71 CoverLab source; versionCode 33077, original signing family")
