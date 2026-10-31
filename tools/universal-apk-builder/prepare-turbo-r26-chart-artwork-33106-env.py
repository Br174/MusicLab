#!/usr/bin/env python3
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r25-spotify-first-charts-33105-env.py", run_name="__main__")
f = Path("uab-project.env")
previous = f.read_text()
assert '33105' in previous
assert 'Revisione-25-Libreria-Spotify-First-Charts' in previous
new = previous.replace('33105','33106').replace(
    'Revisione-25-Libreria-Spotify-First-Charts',
    'Revisione-26-Classifiche-Copertine')
assert '33106' in new and 'Revisione-26-Classifiche-Copertine' in new
f.write_text(new)
print("[MUSICLAB 33106] In-place signed update from verified 33105")
