#!/usr/bin/env python3
"""LAB63 signed same-family Android update, versionCode 33065."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab62-progressive-cover-env.py", run_name="__main__")
path = Path("uab-project.env")
text = path.read_text(encoding="utf-8")
changes = {
    'UAB_FILE_VERSION="TURBO_LAB62"': 'UAB_FILE_VERSION="TURBO_LAB63"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB62"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB63"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 62"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 63"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33064"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33065"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 62"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 63"',
    'export MUSICLAB_VERSION_CODE="33064"': 'export MUSICLAB_VERSION_CODE="33065"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab62-progressive-cover"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab63-anr-round-identity"',
}
for old, new in changes.items():
    if text.count(old) != 1:
        raise SystemExit("LAB62 baseline differs: " + old)
    text = text.replace(old, new, 1)
for required in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:
        raise SystemExit("Android family changed: " + required)
path.write_text(text, encoding="utf-8")
print("LAB63 same Android package/certificate, versionCode 33065")
