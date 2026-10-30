#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r14-cycle-source-env.py', run_name='__main__')
f=Path('uab-project.env')
old=f.read_text()
assert 'Revisione-14' in old and '33087' in old, 'R14 baseline not verified'
new=old.replace('Revisione-14','Revisione-15').replace('33087','33088')
assert new!=old
f.write_text(new)
print('R15: same signed Turbo update family, versionCode=33088')
