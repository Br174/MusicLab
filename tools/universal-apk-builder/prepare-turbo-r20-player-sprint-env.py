#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r19-source-priority-env.py",run_name="__main__")
path=Path("uab-project.env")
text=path.read_text()
assert "Revisione-19" in text and "33092" in text
new=text.replace("Revisione-19","Revisione-20").replace("33092","33093")
assert new!=text
path.write_text(new)
print("R20 audio Player Sprint: same signed Turbo family, versionCode=33093")
