#!/usr/bin/env python3
"""LAB47: update-in-place stamp for the isolated Cover search/video repair."""
from pathlib import Path
import runpy

runpy.run_path(
    "tools/universal-apk-builder/prepare-turbo-lab46-cloud-behavior-progressive-env.py",
    run_name="__main__",
)
path = Path("uab-project.env")
text = path.read_text(encoding="utf-8")
changes = {
    'UAB_FILE_VERSION="TURBO_LAB46"': 'UAB_FILE_VERSION="TURBO_LAB47"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-CANDIDATA-LAB46"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB47"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 46"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 47"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33048"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33049"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 46"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 47"',
    'export MUSICLAB_VERSION_CODE="33048"': 'export MUSICLAB_VERSION_CODE="33049"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-candidata-lab46-clean"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab47-coverfix"',
}
for old, new in changes.items():
    if text.count(old) != 1:
        raise SystemExit(f"Refusing LAB47 stamp: unexpected source field {old}")
    text = text.replace(old, new, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in text:
    raise SystemExit("Wrong installed app family")
if 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"' not in text:
    raise SystemExit("Signing certificate identity mismatch")
path.write_text(text, encoding="utf-8")
print("MusicLab LAB47: app family turbo01, versionCode 33049, verified signing profile unchanged")
