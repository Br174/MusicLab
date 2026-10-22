#!/usr/bin/env python3
"""Turbo LAB45 separate video and cover actions with 50-result progressive deduplicated rounds stamp: same app family/signing, monotonic recovery code."""
from pathlib import Path

config = Path("uab-project.env")
text = config.read_text(encoding="utf-8")

exact = {
    'UAB_FILE_VERSION="TURBO_LAB19"': 'UAB_FILE_VERSION="TURBO_LAB45"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB19"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB45"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 19"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 45"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="1019"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33045"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 19"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 45"',
    'export MUSICLAB_VERSION_CODE="1019"': 'export MUSICLAB_VERSION_CODE="33045"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab19"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab45"',
}

for old, new in exact.items():
    if text.count(old) != 1:
        raise SystemExit("LAB45 recovery stamp rejected: unexpected verified Turbo baseline")
    text = text.replace(old, new, 1)

if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in text:
    raise SystemExit("LAB45 recovery stamp rejected: wrong app family")

expected_cert = 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'
if expected_cert not in text:
    raise SystemExit("LAB45 recovery stamp rejected: signing identity changed")

config.write_text(text, encoding="utf-8")
print("LAB45 Android 33045 recovery identity set, package/signing unchanged")
