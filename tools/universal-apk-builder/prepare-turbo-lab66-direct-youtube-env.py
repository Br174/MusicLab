#!/usr/bin/env python3
"""LAB66: same signed Android identity, metadata-only UAB environment."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab65-video-first-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
changes={
 'UAB_FILE_VERSION="TURBO_LAB65"':'UAB_FILE_VERSION="TURBO_LAB66"',
 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB65"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB66"',
 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 65"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 66"',
 'UAB_UPDATE_FAMILY_VERSION_CODE="33067"':'UAB_UPDATE_FAMILY_VERSION_CODE="33068"',
 'export METROLIST_APP_NAME="MusicLab Turbo LAB 65"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 66"',
 'export MUSICLAB_VERSION_CODE="33067"':'export MUSICLAB_VERSION_CODE="33068"',
 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab65-video-first"':
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab66-direct-youtube-rounds"',
}
for old,new in changes.items():
 if s.count(old)!=1: raise SystemExit("LAB65 inherited identity changed: "+old)
 s=s.replace(old,new)
for required in (
 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
 'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"',
):
 if required not in s: raise SystemExit("Signed install identity changed: "+required)
p.write_text(s,encoding="utf-8")
print("LAB66: versionCode=33068, exact signed Turbo package family")
