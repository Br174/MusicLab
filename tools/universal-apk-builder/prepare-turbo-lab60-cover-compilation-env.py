#!/usr/bin/env python3
"""LAB60: same Turbo family 01 and signer, monotonic Android version 33062."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab59-video-first-env.py", run_name="__main__")
path=Path("uab-project.env")
text=path.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB59"':'UAB_FILE_VERSION="TURBO_LAB60"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB59"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB60"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 59"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 60"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33061"':'UAB_UPDATE_FAMILY_VERSION_CODE="33062"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 59"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 60"',
 'export MUSICLAB_VERSION_CODE="33061"':'export MUSICLAB_VERSION_CODE="33062"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab59-video-first"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab60-cover-compilation"',
}
for old,new in changes.items():
    if text.count(old)!=1:raise SystemExit("LAB59 baseline differs: "+old)
    text=text.replace(old,new,1)
for required in (
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:raise SystemExit("App identity/cert must stay unchanged: "+required)
path.write_text(text,encoding="utf-8")
print("LAB60: exact identity/signature preserved; versionCode 33062")
