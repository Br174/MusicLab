#!/usr/bin/env python3
"""MusicLab Turbo LAB65 video-first same-family Android update, versionCode 33067."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab64-fluid-progressive-env.py", run_name="__main__")
path = Path("uab-project.env")
text = path.read_text(encoding="utf-8")
changes = {
    'UAB_FILE_VERSION="TURBO_LAB64"': 'UAB_FILE_VERSION="TURBO_LAB65"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB64"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB65"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 64"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 65"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33066"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33067"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 64"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 65"',
    'export MUSICLAB_VERSION_CODE="33066"': 'export MUSICLAB_VERSION_CODE="33067"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab64-fluid-progressive"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab65-video-first"',
}
for old, new in changes.items():
    if text.count(old) != 1:
        raise SystemExit("LAB64 baseline changed: " + old)
    text = text.replace(old, new, 1)
for required in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:
        raise SystemExit("Android identity changed: " + required)
path.write_text(text, encoding="utf-8")
print("LAB65 versionCode=33067, same Android package ID and signing family")
