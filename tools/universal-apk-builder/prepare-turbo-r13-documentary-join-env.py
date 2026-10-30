#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path('tools/universal-apk-builder/prepare-turbo-r12-video-throughput-env.py',run_name='__main__')
p=Path('uab-project.env')
s=p.read_text()
assert 'Revisione-12' in s and '33085' in s
new=s.replace('Revisione-12','Revisione-13').replace('33085','33086')
assert new!=s
p.write_text(new)
print('R13 same signed Android family, versionCode33086')
