#!/usr/bin/env python3
"""LAB50: preserve LAB49 signed same-package update family."""
from pathlib import Path
import runpy

runpy.run_path(
    "tools/universal-apk-builder/prepare-turbo-lab49-audio-stability-env.py",
    run_name="__main__",
)
path = Path("uab-project.env")
text = path.read_text(encoding="utf-8")
changes = {
    'UAB_FILE_VERSION="TURBO_LAB49"': 'UAB_FILE_VERSION="TURBO_LAB50"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB49"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB50"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 49"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 50"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33051"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33052"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 49"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 50"',
    'export MUSICLAB_VERSION_CODE="33051"': 'export MUSICLAB_VERSION_CODE="33052"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab49-audio-stability"':
        'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab50-progressive-covers"',
}
for previous, next_value in changes.items():
    if text.count(previous) != 1:
        raise SystemExit(f"LAB50: source version stamp mismatch: {previous}")
    text = text.replace(previous, next_value, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in text:
    raise SystemExit("LAB50: app ID mismatch")
if 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"' not in text:
    raise SystemExit("LAB50: certificate mismatch")
path.write_text(text, encoding="utf-8")
print("LAB50: compatible versionCode 33052, signed Turbo family intact")
