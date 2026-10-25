#!/usr/bin/env python3
"""LAB58: preserve LAB57 Android identity and signing, version 33060."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab57-anr-root-env.py",run_name="__main__")
p=Path("uab-project.env")
text=p.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB57"':'UAB_FILE_VERSION="TURBO_LAB58"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB57"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB58"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 57"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 58"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33059"':'UAB_UPDATE_FAMILY_VERSION_CODE="33060"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 57"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 58"',
 'export MUSICLAB_VERSION_CODE="33059"':'export MUSICLAB_VERSION_CODE="33060"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab57-anr-root"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab58-anr-trace"',
}
for old,new in changes.items():
    if text.count(old)!=1:raise SystemExit(f"Missing LAB57 family token {old}")
    text=text.replace(old,new,1)
for invariant in (
  'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
  'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if invariant not in text:raise SystemExit("Android family mismatch: "+invariant)
p.write_text(text,encoding="utf-8")
print("LAB58 33060: same package, signer, no data loss")
