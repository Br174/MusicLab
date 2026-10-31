#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r25-partial-cycles-audio-env.py",run_name="__main__")
p=Path("uab-project.env")
value=p.read_text()
assert "Revisione-25" in value and "33097" in value
p.write_text(value.replace("Revisione-25","Revisione-25-Libreria-Spotify-First").replace("33097","33104"))
print("[SPOTIFY FIRST] signed same-family Android versionCode=33104")
