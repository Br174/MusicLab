#!/usr/bin/env python3
"""R70: same signed Turbo family; repair Spotify WebView login session handling only."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r69-33149-env.py", run_name="__main__")
p = Path("uab-project.env")
source = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33149"' in source
assert 'export MUSICLAB_VERSION_CODE="33149"' in source
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert 'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert "Revisione-69-Compilation-Sottotitolo-Pulito" in source
# UAB metadata and Gradle's exported versionCode must be changed together.
# The previous R70 altered only UAB metadata; the compiled APK stayed at 33149.
updated = source.replace('UAB_UPDATE_FAMILY_VERSION_CODE="33149"',
                         'UAB_UPDATE_FAMILY_VERSION_CODE="33150"', 1)
updated = updated.replace('export MUSICLAB_VERSION_CODE="33149"',
                          'export MUSICLAB_VERSION_CODE="33150"', 1)
updated = updated.replace("Revisione-69-Compilation-Sottotitolo-Pulito",
                          "Revisione-70-Spotify-Accesso-Stabile", 1)
assert updated.count('UAB_UPDATE_FAMILY_VERSION_CODE="33150"') == 1
assert updated.count('export MUSICLAB_VERSION_CODE="33150"') == 1
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
assert 'export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
p.write_text(updated)
print("[R70] Spotify session recovery, Turbo same-family versionCode 33150")
