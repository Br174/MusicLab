#!/usr/bin/env python3
"""R65: same signed MusicLab Turbo update family as verified R64."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r64-33144-env.py", run_name="__main__")
p=Path("uab-project.env")
s=p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33144"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
assert "Revisione-64-Copertine-Smart-Leggere" in s
s=s.replace("33144","33145")
s=s.replace("Revisione-64-Copertine-Smart-Leggere",
            "Revisione-65-Filtri-Cover-Date-Certificate")
p.write_text(s)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33145"' in s
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in s
print("[R65] MusicLab Turbo signed identity preserved, versionCode=33145")
