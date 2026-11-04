#!/usr/bin/env python3
"""R62 exact source diff and native player signature protection."""
import subprocess
from pathlib import Path

BASE="34df0f46f46fff1dfe57d1698fde453d555b687c"
P="app/src/main/kotlin/com/metrolist/music/"
ALLOWED={
 ".github/workflows/musiclab-r62-universal-swipe-autoplay-33142-apk.yml",
 P+"playback/MusicService.kt",
 P+"playback/PlayerConnection.kt",
 P+"playback/MusicLabSectionPlayback.kt",
 P+"playback/OrderedResultAutoplayPolicy.kt",
 P+"ui/component/DiscogsDirectVersionBrowser.kt",
 P+"ui/player/Thumbnail.kt",
 P+"ui/screens/search/OnlineSearchResult.kt",
 "app/src/test/kotlin/com/metrolist/music/playback/OrderedResultAutoplayPolicyTest.kt",
 "cloudflare/music-intelligence/test/musiclab-r62-universal-swipe-autoplay.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r62-33142-env.py",
 "tools/universal-apk-builder/verify-r62-33142-scope.py",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert changed==ALLOWED, "R62 scope invalid: "+repr(sorted(changed.symmetric_difference(ALLOWED)))
for protected in (
 P+"ui/component/CoverSwipeBridge.kt",
 P+"ui/component/CompilationScreen.kt",
 P+"ui/screens/search/MusicLabChartScreen.kt",
 P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
 P+"ui/screens/search/MusicLabHistoricalExtraCharts.kt",
 P+"playback/StreamUrlCache.kt",
 P+"playback/PlaybackSprintPolicy.kt",
 P+"utils/InnerTubeXPlayer.kt",
 P+"ui/player/Player.kt",
 P+"ui/player/MiniPlayer.kt",
 P+"discogs/CompilationTrackResolver.kt",
 ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
 assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected),"protected "+protected
service=Path(P+"playback/MusicService.kt").read_text()
sec=Path(P+"playback/MusicLabSectionPlayback.kt").read_text()
thumb=Path(P+"ui/player/Thumbnail.kt").read_text()
assert "canAdvanceOrderedResults()" in service
assert "PLAYBACK_START_BUFFER_MS = 1_600" in service
assert "R59_RESTORE_NO_DELAYED_SEEK" in service
assert "fun onPlaybackEnded(" in sec
assert "scheduleNeighbour(connection, current.source, next," in sec
assert "OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(" in sec
assert "activeEditorialContext" in thumb
assert "pointerInput(swipeThumbnail)" in thumb
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R62] scope verified, Cover/R61 visuals and native audio controls protected")
