#!/usr/bin/env python3
"""Prepare R29 historical Hit Parade as an in-place update of signed R28 33108."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r28-real-artwork-33108-env.py", run_name="__main__")
path = Path("uab-project.env")
previous = path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33108"' in previous
assert "Revisione-28-Classifiche-Copertine-Reali" in previous
updated = previous.replace("33108", "33109").replace(
    "Revisione-28-Classifiche-Copertine-Reali", "Revisione-29-Hit-Parade")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33109"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
path.write_text(updated)
print("[MUSICLAB 33109] Uses existing 33108 application ID and original signing family")
