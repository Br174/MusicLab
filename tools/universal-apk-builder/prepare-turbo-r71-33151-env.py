#!/usr/bin/env python3
"""R71: isolated reversible Spotify Android User-Agent experiment. No official OAuth claims."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r70-33150-env.py", run_name="__main__")
p = Path("uab-project.env")
source = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33150"' in source
assert 'export MUSICLAB_VERSION_CODE="33150"' in source
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert 'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert "Revisione-70-Spotify-Accesso-Stabile" in source
updated = source.replace('UAB_UPDATE_FAMILY_VERSION_CODE="33150"',
                         'UAB_UPDATE_FAMILY_VERSION_CODE="33151"', 1)
updated = updated.replace('export MUSICLAB_VERSION_CODE="33150"',
                          'export MUSICLAB_VERSION_CODE="33151"', 1)
updated = updated.replace("Revisione-70-Spotify-Accesso-Stabile",
                          "Revisione-71-Spotify-Accesso-Android-Test", 1)
assert updated.count('UAB_UPDATE_FAMILY_VERSION_CODE="33151"') == 1
assert updated.count('export MUSICLAB_VERSION_CODE="33151"') == 1
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
assert 'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
p.write_text(updated)
print("[R71] Android UA A/B experiment only; Turbo test family versionCode 33151")
