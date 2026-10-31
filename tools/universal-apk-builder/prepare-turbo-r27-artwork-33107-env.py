#!/usr/bin/env python3
"""Prepare signed 33107 using the identical package/signing family as 33106."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r26-chart-artwork-33106-env.py", run_name="__main__")
path = Path("uab-project.env")
previous = path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33106"' in previous
assert "Revisione-26-Classifiche-Copertine" in previous
new = previous.replace("33106", "33107").replace(
    "Revisione-26-Classifiche-Copertine", "Revisione-27-Copertine-Ricerca")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33107"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
path.write_text(new)
print("[MUSICLAB 33107] Same signed Android family as verified 33106")
