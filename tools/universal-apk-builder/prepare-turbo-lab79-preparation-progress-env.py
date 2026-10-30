#!/usr/bin/env python3
"""LAB79: same signed Turbo family, preparation timing/retry correction only."""
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-lab78-staged25-env.py', run_name='__main__')
p = Path('uab-project.env')
s = p.read_text(encoding='utf-8')
changes = {
    'UAB_FILE_VERSION="TURBO_LAB78"': 'UAB_FILE_VERSION="TURBO_LAB79"',
    'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB78"': 'UAB_OUTPUT_NAME="MusicLab-Turbo-LAB79-Revisione-8"',
    'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 78"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 79"',
    'UAB_UPDATE_FAMILY_VERSION_CODE="33080"': 'UAB_UPDATE_FAMILY_VERSION_CODE="33081"',
    'export METROLIST_APP_NAME="MusicLab Turbo LAB 78"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 79"',
    'export MUSICLAB_VERSION_CODE="33080"': 'export MUSICLAB_VERSION_CODE="33081"',
    'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab78-staged25"': 'export MUSICLAB_VERSION_NAME="0.9.14-turbo-lab79-preparation-progress"',
}
for old, new in changes.items():
    if s.count(old) != 1:
        raise SystemExit('Unexpected LAB78 family setting: ' + old)
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('LAB79 same Android signed family: versionCode33081')
