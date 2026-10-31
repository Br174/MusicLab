#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r30-lastfm-credentials-env.py",run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert "33102" in s and "Revisione-30" in s
p.write_text(s.replace("33102","33103").replace("Revisione-30","Revisione-31"))
print("[R31] Last.fm geo JSON + standard artwork rows; Android versionCode 33103")
