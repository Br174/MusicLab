#!/usr/bin/env python3
"""LAB59 VIDEO FIRST: same Turbo Android family and signer, versionCode 33061."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab58-anr-trace-env.py", run_name="__main__")
p=Path("uab-project.env")
text=p.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB58"':'UAB_FILE_VERSION="TURBO_LAB59"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB58"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB59"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 58"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 59"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33060"':'UAB_UPDATE_FAMILY_VERSION_CODE="33061"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 58"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 59"',
 'export MUSICLAB_VERSION_CODE="33060"':'export MUSICLAB_VERSION_CODE="33061"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab58-anr-trace"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab59-video-first"',
}
for old,new in changes.items():
    if text.count(old)!=1:raise SystemExit("LAB58 token mismatch: "+old)
    text=text.replace(old,new,1)
for required in (
  'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
  'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:raise SystemExit("Android identity mismatch: "+required)
p.write_text(text,encoding="utf-8")
print("LAB59 video-first; installed-app update, same signing, versionCode=33061")
