#!/usr/bin/env python3
from pathlib import Path
import runpy

runpy.run_path('tools/universal-apk-builder/prepare-turbo-r13-documentary-join-env.py', run_name='__main__')
env = Path('uab-project.env')
old = env.read_text()
assert 'Revisione-13' in old and '33086' in old
new = old.replace('Revisione-13', 'Revisione-14').replace('33086', '33087')
assert new != old
env.write_text(new)
print('R14: same signed Turbo family; versionCode=33087')
