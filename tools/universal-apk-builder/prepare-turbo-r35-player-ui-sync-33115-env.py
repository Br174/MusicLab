#!/usr/bin/env python3
"""MusicLab R35 player UI+audio synchronization, same signed Android family."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r34-player-fluido-33114-env.py",run_name="__main__")
env=Path("uab-project.env")
old=env.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33114"' in old
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in old
assert "Revisione-34-Player-Fluido" in old
new=old.replace("33114","33115").replace(
    "Revisione-34-Player-Fluido","Revisione-35-Player-Sincronizzato")
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33115"' in new
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in new
assert "33114" not in new
env.write_text(new)
print("[MUSICLAB R35] signed in-place update 33115; previous charts/Cover unchanged")
