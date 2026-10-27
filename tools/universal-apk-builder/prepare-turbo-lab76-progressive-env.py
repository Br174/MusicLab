#!/usr/bin/env python3
"""LAB76: LAB75 signed family, only CoverLab progressive-round logic changes."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab75-exact-lab71-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
change={
 'UAB_FILE_VERSION="TURBO_LAB75"':'UAB_FILE_VERSION="TURBO_LAB76"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB75"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB76"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 75"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 76"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33077"':'UAB_UPDATE_FAMILY_VERSION_CODE="33078"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 75"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 76"',
 'export MUSICLAB_VERSION_CODE="33077"':'export MUSICLAB_VERSION_CODE="33078"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab75-exact-lab71-restore"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab76-progressive-button"',
}
for a,b in change.items():
    if s.count(a)!=1: raise SystemExit("Unexpected LAB75 family config: "+a)
    s=s.replace(a,b,1)
for sentinel in (
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'
):
    if sentinel not in s: raise SystemExit("Existing signer or package cannot change")
p.write_text(s,encoding="utf-8")
print("LAB76 same Android family signed update: versionCode33078")
