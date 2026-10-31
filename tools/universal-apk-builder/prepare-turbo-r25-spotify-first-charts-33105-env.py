#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r25-library-spotify-first-env.py",run_name="__main__")
f=Path("uab-project.env")
value=f.read_text()
assert '33104' in value and 'Revisione-25-Libreria-Spotify-First' in value
f.write_text(value.replace('33104','33105').replace('Revisione-25-Libreria-Spotify-First','Revisione-25-Libreria-Spotify-First-Charts'))
print("[MUSICLAB 33105] R25 Cover + Spotify First + R31 Charts; same package/signature")
