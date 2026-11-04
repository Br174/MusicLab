#!/usr/bin/env python3
"""R63 scoped gesture-only fix from signed R62; native audio unaffected."""
from pathlib import Path
import subprocess

BASE="4e19b9b74b6b4476ff0c4d0c693facdb420f7048"
P="app/src/main/kotlin/com/metrolist/music/"
ALLOWED={
    ".github/workflows/musiclab-r63-universal-swipe-input-33143-apk.yml",
    P+"ui/player/Thumbnail.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/MusicLabSwipeInputPolicy.kt",
    P+"ui/component/DiscogsDirectVersionBrowser.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/MusicLabSwipeInputPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r63-universal-swipe-input.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r63-33143-env.py",
    "tools/universal-apk-builder/verify-r63-33143-scope.py",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert changed==ALLOWED,"R63 changed files mismatch: "+repr(sorted(changed.symmetric_difference(ALLOWED)))
for protected in (
    P+"playback/MusicService.kt",P+"playback/PlayerConnection.kt",
    P+"playback/PlaybackSprintPolicy.kt",P+"playback/StreamUrlCache.kt",
    P+"playback/OrderedResultAutoplayPolicy.kt",
    P+"ui/component/CoverSwipeBridge.kt",
    P+"ui/component/CompilationScreen.kt",
    P+"ui/screens/search/MusicLabChartScreen.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/OnlineSearchResult.kt",
    P+"ui/player/Player.kt",P+"ui/player/MiniPlayer.kt",
    P+"utils/InnerTubeXPlayer.kt",
    P+"discogs/CompilationTrackResolver.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected),"Protected module: "+protected
thumb=Path(P+"ui/player/Thumbnail.kt").read_text()
section=Path(P+"playback/MusicLabSectionPlayback.kt").read_text()
cover=Path(P+"ui/component/DiscogsDirectVersionBrowser.kt").read_text()
assert "detectHorizontalDragGestures(" in thumb
assert "userScrollEnabled = false" in thumb
assert "rememberUpdatedState(isPlayerExpanded)" in thumb
assert "PointerEventPass.Final" not in thumb
assert "R61VerifiedCoverArtwork" in thumb
assert "MusicLabSwipeInputPolicy.nextTarget(" in section
assert "fun onPlaybackEnded(" in section and "scheduleNeighbour(" in section
assert "val nearby = sequenceIndex >= 0" in cover
assert "CoverSwipeBridge.expectIncoming(sessionKey, selectedId)" in cover
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R63] verified gesture-only changes; R59 audio, R61 art and R62 autoplay protected")
