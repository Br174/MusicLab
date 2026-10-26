#!/usr/bin/env python3
"""LAB61 append-only UI, same Turbo Android update family/cert, code 33063."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab60-cover-compilation-env.py", run_name="__main__")
path=Path("uab-project.env")
text=path.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB60"':'UAB_FILE_VERSION="TURBO_LAB61"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB60"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB61"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 60"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 61"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33062"':'UAB_UPDATE_FAMILY_VERSION_CODE="33063"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 60"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 61"',
 'export MUSICLAB_VERSION_CODE="33062"':'export MUSICLAB_VERSION_CODE="33063"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab60-cover-compilation"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab61-append-scroll"',
}
for old,new in changes.items():
    if text.count(old)!=1:raise SystemExit("LAB60 baseline differs: "+old)
    text=text.replace(old,new,1)
for required in (
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:raise SystemExit("App identity/cert changed: "+required)
path.write_text(text,encoding="utf-8")
print("LAB61: same Android family and signer; versionCode=33063")
