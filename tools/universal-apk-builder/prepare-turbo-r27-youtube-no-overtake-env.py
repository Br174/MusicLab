#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r26-source-order-only-env.py",run_name="__main__")
p=Path("uab-project.env")
value=p.read_text()
assert "Revisione-26" in value and "33098" in value
value=value.replace("Revisione-26", "Revisione-27").replace("33098", "33099")
p.write_text(value)
print("[R27] verified update-family MusicLab Turbo Android versionCode=33099")
