#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r15-root-cause-env.py', run_name='__main__')
f=Path('uab-project.env')
old=f.read_text()
assert 'Revisione-15' in old and '33088' in old, 'R14 baseline not verified'
new=old.replace('Revisione-15','Revisione-16').replace('33088','33089')
assert new!=old
f.write_text(new)
print('Video First: same signed Turbo update family, versionCode=33089')
