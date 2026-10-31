#!/usr/bin/env python3
"""R27 exact signed R26 descent, YouTube scheduling only; no exclusion or audio changes."""
import subprocess
BASE="f5aadae569e526fa8203bd60bdfc1c5d58ede5c1"
allowed={
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR19SourcePriorityTest.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR27YoutubeNoOvertakeTest.kt",
 "cloudflare/music-intelligence/test/turbo-r27-youtube-no-overtake.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r27-youtube-no-overtake-env.py",
 "tools/universal-apk-builder/verify-r27-youtube-no-overtake-scope.py",
 ".github/workflows/musiclab-turbo-r27-youtube-no-overtake-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes <= allowed, "Unexpected R27 changes: "+str(sorted(changes-allowed))
assert {
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
} <= changes
for protected in [
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
 "behaviors/musiclab-cover.json",
]:
 assert protected not in changes, protected
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R27 SCOPE=PASS] YouTube non-overtake; no discovery, admission, packet or player changes")
