#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r29-verified-charts-env.py",run_name="__main__")
path = Path("uab-project.env")
value = path.read_text()
assert '33101' in value and 'Revisione-29' in value
path.write_text(value.replace('33101', '33102').replace('Revisione-29', 'Revisione-30'))
print('[R30] credentials already stored in MusicLab DataStore; Android update 33102')
