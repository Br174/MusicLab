#!/usr/bin/env python3
"""LAB67 exact LAB66 signing family upgrade, versionCode 33069."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab66-youtube-streaming-env.py", run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
for a,b in {
  'UAB_FILE_VERSION="TURBO_LAB66"':'UAB_FILE_VERSION="TURBO_LAB67"',
  'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB66"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB67"',
  'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 66"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 67"',
  'UAB_UPDATE_FAMILY_VERSION_CODE="33068"':'UAB_UPDATE_FAMILY_VERSION_CODE="33069"',
  'export METROLIST_APP_NAME="MusicLab Turbo LAB 66"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 67"',
  'export MUSICLAB_VERSION_CODE="33068"':'export MUSICLAB_VERSION_CODE="33069"',
  'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab66-youtube-streaming"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab67-quality-fluidity"',
}.items():
    if s.count(a)!=1: raise SystemExit("LAB66 baseline changed: "+a)
    s=s.replace(a,b,1)
for required in (
  'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
  'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in s: raise SystemExit("Identity violation: "+required)
p.write_text(s,encoding="utf-8")
print("LAB67 versionCode=33069, unchanged app ID and signature")
