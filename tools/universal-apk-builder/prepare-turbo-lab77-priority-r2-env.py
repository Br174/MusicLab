#!/usr/bin/env python3
"""LAB77 R2: existing signed Turbo family; version advancement only in its adapter."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab76-progressive-env.py", run_name="__main__")
p = Path("uab-project.env")
s = p.read_text(encoding="utf-8")
changes = {
    'UAB_FILE_VERSION="TURBO_LAB76"': 'UAB_FILE_VERSION="TURBO_LAB77"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB76"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB77"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 76"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 77"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33078"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33079"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 76"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 77"',
    'export MUSICLAB_VERSION_CODE="33078"': 'export MUSICLAB_VERSION_CODE="33079"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab76-progressive-button"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab77-priority-r2"',
}
for old, new in changes.items():
    if s.count(old) != 1:
        raise SystemExit("Unexpected LAB76 family config: " + old)
    s = s.replace(old, new, 1)
for sentinel in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if sentinel not in s:
        raise SystemExit("Existing signer or package cannot change")
p.write_text(s, encoding="utf-8")
print("LAB77 same Android signed family: versionCode33079")
