#!/usr/bin/env python3
"""LAB20 runner-only version stamp; preserves all signing material verbatim."""
from pathlib import Path

config = Path("uab-project.env")
text = config.read_text(encoding="utf-8")
# Never edit or print the keystore, aliases, passwords, or signing certificate.
exact = {
    'UAB_FILE_VERSION="TURBO_LAB19"': 'UAB_FILE_VERSION="TURBO_LAB20"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB19"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB20"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 19"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 20"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="1019"': 'UAB_UPDATE_FAMILY_VERSION_CODE="1020"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 19"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 20"',
    'export MUSICLAB_VERSION_CODE="1019"': 'export MUSICLAB_VERSION_CODE="1020"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab19"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab20"',
}
for old, new in exact.items():
    if text.count(old) != 1:
        raise SystemExit("LAB20 identity stamp blocked: baseline mismatch")
    text = text.replace(old, new, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in text:
    raise SystemExit("LAB20 identity stamp blocked: application family mismatch")
config.write_text(text, encoding="utf-8")
print("LAB20 identity overlay checked: 1020, Turbo family retained; signing untouched")
