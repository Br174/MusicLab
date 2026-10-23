#!/usr/bin/env python3
"""LAB48: safe update-in-place from LAB47, without modifying signing material."""
from pathlib import Path
import runpy

runpy.run_path(
    "tools/universal-apk-builder/prepare-turbo-cover-dedupe-lab47-env.py",
    run_name="__main__",
)
file = Path("uab-project.env")
body = file.read_text(encoding="utf-8")
replacements = {
    'UAB_FILE_VERSION="TURBO_LAB47"': 'UAB_FILE_VERSION="TURBO_LAB48"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB47"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB48"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 47"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 48"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33049"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33050"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 47"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 48"',
    'export MUSICLAB_VERSION_CODE="33049"': 'export MUSICLAB_VERSION_CODE="33050"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab47-coverfix"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab48-work-first"',
}
for previous, next_value in replacements.items():
    if body.count(previous) != 1:
        raise SystemExit(f"LAB48 stamp rejected: {previous}")
    body = body.replace(previous, next_value, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in body:
    raise SystemExit("LAB48 rejected: family changed")
if 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"' not in body:
    raise SystemExit("LAB48 rejected: signing certificate changed")
file.write_text(body, encoding="utf-8")
print("LAB48: version 33050, package and signing profile preserved")
