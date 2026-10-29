#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py', run_name='__main__')
p = Path('uab-project.env')
s = p.read_text().replace('Revisione-8', 'Revisione-9').replace('33081', '33082')
p.write_text(s)
print('R9 same signed Android family, versionCode33082')
