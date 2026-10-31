#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r25-partial-cycles-audio-env.py",run_name="__main__")
p=Path("uab-project.env")
current=p.read_text()
assert "Revisione-25" in current and "33097" in current
new=current.replace("Revisione-25", "Revisione-26").replace("33097", "33098")
p.write_text(new)
print("[R26] Signed same-family MusicLab Turbo position-only APK 33098")
