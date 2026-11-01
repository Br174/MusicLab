#!/usr/bin/env python3
"""MusicLab R35 exact lineage & guarded UI/audio synchronization scope."""
import subprocess
from pathlib import Path

BASE="9164dc7ef94b320015a86999f018279dd7fa1e5b"
EXPECTED={
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt",
 "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
 "cloudflare/music-intelligence/test/musiclab-r35-player-ui-sync.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r35-player-ui-sync-33115-env.py",
 "tools/universal-apk-builder/verify-r35-player-ui-sync-33115-scope.py",
 ".github/workflows/musiclab-r35-player-ui-sync-33115-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed==EXPECTED,"Unexpected files in R35: "+repr(sorted(changed.symmetric_difference(EXPECTED)))

PROTECTED=[
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSwipeBridge.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/queues/ListQueue.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for path in PROTECTED:
 old=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
 new=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
 assert old==new,"Protected player/cover/chart identity changed: "+path

source=Path("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt").read_text()
thumb=Path("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt").read_text()
mini=Path("app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt").read_text()
conn=Path("app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt").read_text()
policy=Path("app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt").read_text()
for v in ("queueRequestSerial","pendingQueuePreparation?.cancel()",
          "pendingQueuePreparation = scope.launch(SilentHandler)",
          "isLatestQueueRequest(requestSerial, queueRequestSerial)"):
 assert v in source,"Unprotected latest user intent: "+v
for v in ("currentMediaItemIndex.collectAsState()","committedSwipeDirection(",
          "CoverSwipeBridge.trySwipe(","playerConnection.seekToNext()",
          "playerConnection.skipToPreviousItem()","thumbnailLazyGridState.scrollToItem(index)"):
 assert v in thumb,"Missing audio/cover synchronization: "+v
assert "snapshotFlow {" not in thumb
assert "animateScrollToItem" not in thumb
assert "fun skipToPreviousItem()" in conn
assert mini.count("playerConnection.seekToNext()")>=2
assert mini.count("playerConnection.skipToPreviousItem()")>=2
assert "isLatestQueueRequest" in policy and "committedSwipeDirection" in policy
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R35 PASS] Source family, old player, covers, charts all protected")
