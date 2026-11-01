#!/usr/bin/env python3
"""R33 Hit Parade real-layout repair: same Android signer and package as 33112."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r32-hitparade-33112-env.py",run_name="__main__")
path=Path("uab-project.env")
old=path.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33112"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-32-Hit-Parade-Reale" in old
new=old.replace("33112","33113").replace(
    "Revisione-32-Hit-Parade-Reale","Revisione-33-Hit-Parade-HTML-Reale")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33113"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33112" not in new
path.write_text(new)
print("[MUSICLAB 33113] Upgrade in place with original package, versionCode and signer")
