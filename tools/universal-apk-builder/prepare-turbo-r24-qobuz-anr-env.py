#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r22-buffer-recovery-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert "Revisione-22" in s and "33095" in s
new=s.replace("Revisione-22","Revisione-24").replace("33095","33096")
assert new!=s
p.write_text(new)
print("MusicLab Turbo R24: same signed family, Android versionCode 33096")
