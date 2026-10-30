#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r18-cover-filters-env.py",run_name="__main__")
path=Path("uab-project.env")
text=path.read_text()
assert "Revisione-18" in text and "33091" in text
new=text.replace("Revisione-18","Revisione-19").replace("33091","33092")
assert new!=text
path.write_text(new)
print("R19 soft source ranking: same signed Turbo family, versionCode=33092")
