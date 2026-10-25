#!/usr/bin/env python3
"""LAB51: retain LAB50 update family and signer, bump only versionCode."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab50-progressive-covers-env.py", run_name="__main__")
path = Path("uab-project.env")
source = path.read_text(encoding="utf-8")
replacements = {
    'UAB_FILE_VERSION="TURBO_LAB50"': 'UAB_FILE_VERSION="TURBO_LAB51"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB50"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB51"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 50"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 51"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33052"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33053"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 50"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 51"',
    'export MUSICLAB_VERSION_CODE="33052"': 'export MUSICLAB_VERSION_CODE="33053"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab50-progressive-covers"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab51-cover-breadth"',
}
for old, new in replacements.items():
    if source.count(old) != 1:
        raise SystemExit(f"LAB51 source family mismatch: {old}")
    source = source.replace(old, new, 1)
for required in [
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
]:
    if required not in source:
        raise SystemExit("LAB51 compatibility identity mismatch")
path.write_text(source, encoding="utf-8")
print("LAB51: update-in-place versionCode 33053, Turbo signer unchanged")
