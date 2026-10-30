#!/usr/bin/env python3
from pathlib import Path
import subprocess
subprocess.run(['python3','tools/universal-apk-builder/verify-r10-cover-scope.py'],check=True)
browser=Path('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt').read_text()
policy=Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPreparationPolicy.kt').read_text()
assert 'metadata == null && sourceCooling("Metadati YouTube")' not in browser, 'Old global metadata freeze persists'
assert 'CoverPreparationPolicy.metadataAttemptAllowed(' in browser
assert 'fun metadataAttemptAllowed(videoId: String, attemptedThisRound: Boolean)' in policy
assert 'takeManualVerifiedPacket' not in browser, 'Unapproved partial publication returned'
assert 'takeManualVerifiedPacket' not in Path('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt').read_text()
print('PASS: metadata attempts scoped to video, unchanged 10+20+20 and player')
