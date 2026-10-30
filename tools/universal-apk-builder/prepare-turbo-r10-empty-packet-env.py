#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r9-recovery-env.py', run_name='__main__')
p=Path('uab-project.env')
s=p.read_text().replace('Revisione-9', 'Revisione-10').replace('33082', '33083')
p.write_text(s)
print('R10 same-family signed update with versionCode 33083')
