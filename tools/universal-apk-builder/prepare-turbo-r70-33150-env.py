#!/usr/bin/env python3
"""R70: same signed Turbo family; repair Spotify WebView login session handling only."""
from pathlib import Path
import runpy

runpy.run_path("tools/universal-apk-builder/prepare-turbo-r69-33149-env.py", run_name="__main__")
p = Path("uab-project.env")
source = p.read_text()
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33149"' in source
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in source
assert "Revisione-69-Compilation-Sottotitolo-Pulito" in source
updated = source.replace('UAB_UPDATE_FAMILY_VERSION_CODE="33149"',
                         'UAB_UPDATE_FAMILY_VERSION_CODE="33150"')
updated = updated.replace("Revisione-69-Compilation-Sottotitolo-Pulito",
                          "Revisione-70-Spotify-Accesso-Stabile")
p.write_text(updated)
assert 'UAB_UPDATE_FAMILY_VERSION_CODE="33150"' in updated
assert 'UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"' in updated
print("[R70] Spotify session recovery, Turbo same-family versionCode 33150")
