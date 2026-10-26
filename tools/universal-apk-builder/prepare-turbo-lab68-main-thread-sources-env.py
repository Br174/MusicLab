#!/usr/bin/env python3
"""LAB68 fixes ExoPlayer wrong-thread crash, accurate source checks; same APK family."""
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-lab67-quality-fluidity-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text(encoding="utf-8")
for a,b in {
    'UAB_FILE_VERSION="TURBO_LAB67"':'UAB_FILE_VERSION="TURBO_LAB68"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB67"':'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB68"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 67"':'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 68"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33069"':'UAB_UPDATE_FAMILY_VERSION_CODE="33070"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 67"':'export METROLIST_APP_NAME="MusicLab Turbo LAB 68"',
    'export MUSICLAB_VERSION_CODE="33069"':'export MUSICLAB_VERSION_CODE="33070"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab67-quality-fluidity"':'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab68-main-thread-sources"',
}.items():
    if s.count(a)!=1:raise SystemExit("LAB67 baseline not exactly matched: "+a)
    s=s.replace(a,b,1)
for guard in ('UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"',
    'UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"'):
    if guard not in s:raise SystemExit("Unexpected Android identity: "+guard)
p.write_text(s,encoding="utf-8")
print("LAB68 versionCode 33070, unchanged native signing family")
