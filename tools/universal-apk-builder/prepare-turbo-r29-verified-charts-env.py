#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r28-search-explore-env.py",run_name="__main__")
p=Path("uab-project.env")
content=p.read_text()
assert "Revisione-28" in content and "33100" in content
p.write_text(content.replace("Revisione-28","Revisione-29").replace("33100","33101"))
print("[R29] MusicLab Turbo Verified Country Charts, Android versionCode 33101")
