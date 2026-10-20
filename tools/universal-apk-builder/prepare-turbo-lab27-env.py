#!/usr/bin/env python3
"""LAB27 temporary runner version stamp: update-in-place, signing unchanged."""
from pathlib import Path

config = Path("uab-project.env")
text = config.read_text(encoding="utf-8")
# The checked-in LAB20 family still has the verified LAB19 metadata base.
# Mutate only seven identity labels inside a disposable GitHub runner.
exact = {
    'UAB_FILE_VERSION="TURBO_LAB19"': 'UAB_FILE_VERSION="TURBO_LAB27"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB19"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB27"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 19"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 27"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="1019"': 'UAB_UPDATE_FAMILY_VERSION_CODE="1027"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 19"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 27"',
    'export MUSICLAB_VERSION_CODE="1019"': 'export MUSICLAB_VERSION_CODE="1027"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab19"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab27"',
}
for old, new in exact.items():
    if text.count(old) != 1:
        raise SystemExit("LAB27 identity stamp rejected: unexpected verified Turbo baseline")
    text = text.replace(old, new, 1)
if 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' not in text:
    raise SystemExit("LAB27 identity stamp rejected: wrong app family")
config.write_text(text, encoding="utf-8")
print("LAB27 Android 1027 identity set, signing material unmodified")
