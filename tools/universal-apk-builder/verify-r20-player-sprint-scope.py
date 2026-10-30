#!/usr/bin/env python3
"""MotorLab Turbo R20: playback-only sprint; Cover discovery and R19 ordering immutable."""
from pathlib import Path
import subprocess

BASE="29377409743ec73cfdcd91e31ad7ec6d804931c5"
allowed={
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
"cloudflare/music-intelligence/test/turbo-lab49-audio-stability-contract.test.mjs",
"cloudflare/music-intelligence/test/turbo-r20-player-sprint.test.mjs",
"tools/universal-apk-builder/verify-r20-player-sprint-scope.py",
"tools/universal-apk-builder/prepare-turbo-r20-player-sprint-env.py",
".github/workflows/musiclab-turbo-r20-player-sprint-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, f"R20 unexpected changes: {changed-allowed}"
subprocess.run(["git","diff","--check",BASE],check=True)
def unchanged(path):
  expected=subprocess.check_output(["git","show",BASE+":"+path],text=True)
  assert Path(path).read_text()==expected, f"R19 source changed: {path}"
for path in [
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverFilterIdentity.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGate.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboTwinGuard.kt",
"behaviors/musiclab-cover.json",
"app/build.gradle.kts",
]: unchanged(path)
b=Path("app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt").read_text()
p=Path("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt").read_text()
assert "isCoverAudioAtRisk() == true" in b
assert "while (isActive && playbackIsCritical()) delay(200L)" in b
assert "fun playResolvedContext" in b and "connection.playNow(selectedItem)" in b
assert "songUrlCache[mediaId]?.let { cachedStream ->" in p
assert "private fun scheduleSmartPlaybackPreload()" in p
assert "PLAYBACK_START_BUFFER_MS = 900" in p and "PLAYBACK_REBUFFER_MS = 6_000" in p
print("R20 PLAYER SPRINT SCOPE PASS: all R19 source ranking, Cover quantity, 6 rounds, filters and signing policy unchanged")
