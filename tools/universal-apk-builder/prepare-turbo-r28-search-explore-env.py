#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r25-partial-cycles-audio-env.py",run_name="__main__")
p=Path("uab-project.env")
content=p.read_text()
assert '33097' in content and "Revisione-25" in content
updated=content.replace("33097","33100").replace("Revisione-25","Revisione-28")
assert updated != content
p.write_text(updated)
print("[R28] MusicLab Turbo Search Explore: original R25 code, Android versionCode 33100")
