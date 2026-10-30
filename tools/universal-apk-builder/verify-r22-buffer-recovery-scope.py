#!/usr/bin/env python3
"""MotorLab MusicLab Turbo R22: only playback/recovery, no Cover admission or family changes."""
import subprocess
BASE="ea0e89fcd01c0dbf5f663f6b424ef2615a6d92e6"
allowed={
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
    "cloudflare/music-intelligence/test/turbo-r22-buffer-recovery.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r22-buffer-recovery-env.py",
    "tools/universal-apk-builder/verify-r22-buffer-recovery-scope.py",
    ".github/workflows/musiclab-turbo-r22-buffer-recovery-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, f"R22 unexpected changes: {changed-allowed}"
for protected in [
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
  "behaviors/musiclab-cover.json",
]:
    assert protected not in changed, f"Protected R17/R19/R21 behavior changed: {protected}"
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R22 SCOPE=PASS] exact signed R21 source preserved, playback-only fixes:", sorted(changed))
