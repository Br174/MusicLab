#!/usr/bin/env python3
"""LAB69: signed update LAB68 family, no package/fingerprint change."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab68-main-thread-sources-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
for old,new in {
  'UAB_FILE_VERSION="TURBO_LAB68"':'UAB_FILE_VERSION="TURBO_LAB69"',
  'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB68"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB69"',
  'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 68"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 69"',
  'UAB_UPDATE_FAMILY_VERSION_CODE="33070"':'UAB_UPDATE_FAMILY_VERSION_CODE="33071"',
  'export METROLIST_APP_NAME="MusicLab Turbo LAB 68"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 69"',
  'export MUSICLAB_VERSION_CODE="33070"':'export MUSICLAB_VERSION_CODE="33071"',
  'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab68-main-thread-sources"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab69-source-status"',
}.items():
    if s.count(old)!=1:raise SystemExit("Unexpected LAB68 baseline: "+old)
    s=s.replace(old,new,1)
for required in (
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
 if required not in s:raise SystemExit("Signing identity violated")
p.write_text(s,encoding="utf-8")
print("LAB69 same-family signed upgrade version 33071")
