#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r21-audio-continuo-env.py",run_name="__main__")
path=Path("uab-project.env")
text=path.read_text()
assert "Revisione-21" in text and "33094" in text
updated=text.replace("Revisione-21","Revisione-22").replace("33094","33095")
assert updated!=text
path.write_text(updated)
print("MusicLab Turbo R22 Buffer Recovery: signed same-family update, versionCode 33095")
