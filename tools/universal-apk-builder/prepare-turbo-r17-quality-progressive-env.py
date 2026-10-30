#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r15-root-cause-env.py',run_name='__main__')
env=Path('uab-project.env')
old=env.read_text()
assert 'Revisione-15' in old and '33088' in old,'R15 signed baseline not verified'
new=old.replace('Revisione-15','Revisione-17').replace('33088','33090')
assert new!=old
env.write_text(new)
print('R17 Quality Progressive: signed Turbo update family, versionCode=33090')
