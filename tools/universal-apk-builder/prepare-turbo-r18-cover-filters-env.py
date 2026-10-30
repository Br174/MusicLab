#!/usr/bin/env python3
from pathlib import Path
import runpy
# Base from exact verified R17 signing identity and versionCode 33090.
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r17-quality-progressive-env.py', run_name='__main__')
p=Path('uab-project.env')
old=p.read_text()
assert 'Revisione-17' in old and '33090' in old
new=old.replace('Revisione-17','Revisione-18').replace('33090','33091')
assert new!=old
p.write_text(new)
print('R18 filter-only: same MusicLab Turbo package and test signing, versionCode 33091')
