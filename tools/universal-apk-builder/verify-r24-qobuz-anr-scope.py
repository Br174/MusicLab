#!/usr/bin/env python3
"""R24 Qobuz ANR patch is exclusive to service startup/settings, not Cover search."""
import subprocess
BASE="21126d03af1a11f57d2a85069276c209fc0ba664"
allowed={
  "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
  "cloudflare/music-intelligence/test/turbo-r24-qobuz-anr.test.mjs",
  "tools/universal-apk-builder/verify-r24-qobuz-anr-scope.py",
  "tools/universal-apk-builder/prepare-turbo-r24-qobuz-anr-env.py",
  ".github/workflows/musiclab-turbo-r24-qobuz-anr-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, "R24 unexpected files: "+str(sorted(changed-allowed))
assert "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt" in changed
for forbidden in (
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
  "behaviors/musiclab-cover.json",
):
  assert forbidden not in changed, forbidden
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R24 SCOPE=PASS] exact R22 baseline; ANR fix isolated; Cover unchanged", sorted(changed))
