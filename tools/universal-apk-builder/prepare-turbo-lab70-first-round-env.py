#!/usr/bin/env python3
"""LAB70: signed update of verified LAB69 Turbo family; package and certificate immutable."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab69-source-status-env.py",run_name="__main__")
p = Path("uab-project.env")
s = p.read_text(encoding="utf-8")
replacements = {
    'UAB_FILE_VERSION="TURBO_LAB69"': 'UAB_FILE_VERSION="TURBO_LAB70"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB69"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB70"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 69"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 70"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33071"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33072"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 69"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 70"',
    'export MUSICLAB_VERSION_CODE="33071"': 'export MUSICLAB_VERSION_CODE="33072"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab69-source-status"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab70-first-round"',
}
for old,new in replacements.items():
    if s.count(old)!=1:
        raise SystemExit("Unexpected LAB69 baseline: "+old)
    s=s.replace(old,new,1)
for required in (
    'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
    if required not in s:
        raise SystemExit("Signing identity violated")
p.write_text(s,encoding="utf-8")
print("LAB70 same-family signed upgrade version 33072")
