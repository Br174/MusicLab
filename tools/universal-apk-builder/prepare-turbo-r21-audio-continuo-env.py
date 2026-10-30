#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r20-player-sprint-env.py",run_name="__main__")
path=Path("uab-project.env")
text=path.read_text()
assert 'Revisione-20' in text and '33093' in text
updated=text.replace('Revisione-20','Revisione-21').replace('33093','33094')
assert updated!=text
path.write_text(updated)
print("MusicLab Turbo R21 Audio Continuo: same signed family, versionCode=33094")
