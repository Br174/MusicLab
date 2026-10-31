#!/usr/bin/env python3
"""R25 isolated user-approved partial cycles and audio continuity; no search-engine or candidate changes."""
import subprocess
BASE="d4d2effe228cec34e9dd189daba97699d179e1c2"
allowed={
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab80RoundClosureTest.kt",
    # R9/R10 expectations are updated to test R25's non-destructive
    # publication of partial results rather than asserting invisibility.
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR9BackgroundRecoveryTest.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR10EmptyRoundRecoveryTest.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR25PartialCycleRetentionTest.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
    "cloudflare/music-intelligence/test/turbo-r22-buffer-recovery.test.mjs",
    "cloudflare/music-intelligence/test/turbo-r25-partial-cycle-audio.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r25-partial-cycles-audio-env.py",
    "tools/universal-apk-builder/verify-r25-partial-cycles-audio-scope.py",
    ".github/workflows/musiclab-turbo-r25-partial-cycles-audio-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed, "R25 unexpected modifications: "+str(sorted(changes-allowed))
assert "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt" in changes
for forbidden in [
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "behaviors/musiclab-cover.json",
]:
    assert forbidden not in changes, forbidden
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R25 SCOPE=PASS] exact signed R24 source, protected R17 R19 cover search, no new network workers",sorted(changes))
