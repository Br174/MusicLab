#!/usr/bin/env python3
"""LAB66 same Android signature + package, versionCode 33068."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab65-video-first-env.py", run_name="__main__")
path = Path("uab-project.env")
text = path.read_text(encoding="utf-8")
replacements = {
    'UAB_FILE_VERSION="TURBO_LAB65"': 'UAB_FILE_VERSION="TURBO_LAB66"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB65"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB66"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 65"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 66"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33067"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33068"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 65"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 66"',
    'export MUSICLAB_VERSION_CODE="33067"': 'export MUSICLAB_VERSION_CODE="33068"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab65-video-first"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab66-youtube-streaming"',
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f"Expected once in inherited UAB environment: {old}")
    text = text.replace(old,new,1)
for required in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in text:
        raise SystemExit(f"Protected Android identity mismatch: {required}")
path.write_text(text, encoding="utf-8")
print("LAB66 signed update family preserved, versionCode 33068")
