#!/usr/bin/env python3
"""Fail closed before MusicLab 33114 signed Player Fluido build."""
import subprocess
from pathlib import Path

BASE="e8c1bab643c5722ff196500aee62e4998764ce82"
EXPECTED={
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
 "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
 "cloudflare/music-intelligence/test/turbo-r25-partial-cycle-audio.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r34-player-fluido-33114-env.py",
 "tools/universal-apk-builder/verify-r34-player-fluido-33114-scope.py",
 ".github/workflows/musiclab-r34-player-fluido-33114-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed==EXPECTED, "R34 unexpected changed source: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
protected=[
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/queues/ListQueue.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for path in protected:
 baseline=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
 actual=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
 assert baseline==actual, "Protected R33 code changed: "+path
service=Path("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt").read_text()
policy=Path("app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt").read_text()
tests=Path("app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt").read_text()
for token in (
 "private const val PLAYBACK_START_BUFFER_MS = 1_600",
 "private const val PLAYBACK_REBUFFER_MS = 8_000",
 "private const val PLAYBACK_PRIORITY_RELEASE_BUFFER_MS = 18_000L",
 "Damaged cache/ranges are cleared only by their dedicated handlers",
 "shouldPurgeCachedAudio(",
 "canRunOptionalBackgroundAudioWork(",
 "if (PlaybackSprintPolicy.shouldPrepareExistingTrack(player.playbackState))",
 "beginPlaybackPriorityBurst(\"play-now\")",
):
 assert token in service, "R34 player contract missing: "+token
for token in ("shouldPurgeCachedAudio", "canRunOptionalBackgroundAudioWork"):
 assert token in policy and token in tests,"R34 policy JUnit absent: "+token
assert "        if (mediaId != null) {\n            performAggressiveCacheClear(mediaId)\n        }" not in service, "Unconditional cache erasure still present"
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R34] Exact 33113 lineage, preserved charts/Cover, safe player policy: PASS")
