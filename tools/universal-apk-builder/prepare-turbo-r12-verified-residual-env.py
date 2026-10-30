#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r11-scudo360-regression-env.py',run_name='__main__')
f=Path('uab-project.env')
a=f.read_text()
assert 'Revisione-11' in a and '33084' in a
b=a.replace('Revisione-11','Revisione-12').replace('33084','33085')
assert b!=a
f.write_text(b)
print('R12 same signed Turbo family versionCode33085')
