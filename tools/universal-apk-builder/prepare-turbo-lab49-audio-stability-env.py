#!/usr/bin/env python3
"""LAB49: signed same-package, monotonically versioned update from LAB48."""
from pathlib import Path
import runpy

runpy.run_path(
    "tools/universal-apk-builder/prepare-turbo-lab48-work-first-env.py",
    run_name="__main__",
)
file = Path("uab-project.env")
body = file.read_text(encoding="utf-8")
replacements = {
    'UAB_FILE_VERSION="TURBO_LAB48"': 'UAB_FILE_VERSION="TURBO_LAB49"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB48"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB49"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 48"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 49"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33050"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33051"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 48"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 49"',
    'export MUSICLAB_VERSION_CODE="33050"': 'export MUSICLAB_VERSION_CODE="33051"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab48-work-first"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab49-audio-stability"',
}
for old, new in replacements.items():
    if body.count(old) != 1:
        raise SystemExit(f"LAB49 stamp refused: {old}")
    body = body.replace(old, new, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in body:
    raise SystemExit("LAB49: Android family mismatch")
if 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"' not in body:
    raise SystemExit("LAB49: signing certificate mismatch")
file.write_text(body, encoding="utf-8")
print("MusicLab LAB49: version 33051, applicationId and signing unchanged")
