#!/usr/bin/env python3
"""MotorLab R21: source diff must be audio-only on exact signed R20."""
import subprocess
from pathlib import Path

BASE="b43196ec3bfdf42e0fd3eb4eb699c9e90833e611"
allowed={
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
    "cloudflare/music-intelligence/test/turbo-r21-audio-continuo.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r21-audio-continuo-env.py",
    "tools/universal-apk-builder/verify-r21-audio-continuo-scope.py",
    ".github/workflows/musiclab-turbo-r21-audio-continuo-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, "R21 unexpected modifications: "+str(sorted(changed-allowed))
assert "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt" in changed
for protected in (
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
    "behaviors/musiclab-cover.json",
):
    assert protected not in changed, "Protected R19/R17 Cover behavior changed: "+protected
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R21 SCOPE=PASS] Exact R20 baseline; changed paths:", sorted(changed))
