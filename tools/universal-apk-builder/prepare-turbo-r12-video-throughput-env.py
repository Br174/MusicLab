#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r11-scudo360-regression-env.py',run_name='__main__')
p=Path('uab-project.env')
s=p.read_text()
assert 'Revisione-11' in s and '33084' in s
changed=s.replace('Revisione-11','Revisione-12').replace('33084','33085')
assert changed!=s
p.write_text(changed)
print('R12 same-family update, original signer, versionCode33085')
