#!/usr/bin/env python3
"""MusicLab 33116 original signature, fast Hit Parade and Compilation audio only."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r35-player-ui-sync-33115-env.py",
    run_name="__main__")
env=Path("uab-project.env")
old=env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33115"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-35-Player-Sincronizzato" in old
new=old.replace("33115","33116").replace(
    "Revisione-35-Player-Sincronizzato","Revisione-36-FastPath-HitParade-Compilation")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33116"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33115" not in new
env.write_text(new)
print("[MUSICLAB 33116] Same Android ID and signing certificate, updated FastPath only")
