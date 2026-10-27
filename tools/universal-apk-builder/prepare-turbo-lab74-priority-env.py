#!/usr/bin/env python3
"""LAB74: positive-only recording priority, signed Android upgrade LAB73→LAB74."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab73-additive-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
changes={
  'UAB_FILE_VERSION="TURBO_LAB73"':'UAB_FILE_VERSION="TURBO_LAB74"',
  'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB73"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB74"',
  'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 73"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 74"',
  'UAB_UPDATE_FAMILY_VERSION_CODE="33075"':'UAB_UPDATE_FAMILY_VERSION_CODE="33076"',
  'export METROLIST_APP_NAME="MusicLab Turbo LAB 73"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 74"',
  'export MUSICLAB_VERSION_CODE="33075"':'export MUSICLAB_VERSION_CODE="33076"',
  'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab73-additive-restore"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab74-priority-only"',
}
for old,new in changes.items():
    if s.count(old)!=1: raise SystemExit("LAB73 signing baseline mismatch: "+old)
    s=s.replace(old,new,1)
for key in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if key not in s: raise SystemExit("Cannot change app family signature or package")
p.write_text(s,encoding="utf-8")
print("LAB74: same package and original signature, versionCode 33076")
