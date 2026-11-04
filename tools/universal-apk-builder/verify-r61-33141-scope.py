#!/usr/bin/env python3
"""R61: only the approved Cover artwork UI and visual ownership change."""
import subprocess
from pathlib import Path

BASE="101e882096c83ab034235f9de1c7430d801b18fd"
P="app/src/main/kotlin/com/metrolist/music/"
ALLOWED={
 ".github/workflows/musiclab-r61-cover-swipe-smooth-artwork-33141-apk.yml",
 P+"ui/component/CoverSwipeBridge.kt",
 P+"ui/component/DiscogsDirectVersionBrowser.kt",
 P+"ui/player/Thumbnail.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverSwipeBridgeTest.kt",
 "cloudflare/music-intelligence/test/musiclab-r61-cover-swipe-smooth-artwork.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r61-33141-env.py",
 "tools/universal-apk-builder/verify-r61-33141-scope.py",
}
def git(*args):
 return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual==ALLOWED,"R61 source scope "+repr(sorted(actual.symmetric_difference(ALLOWED)))
for protected in (
 P+"playback/MusicService.kt",
 P+"playback/PlaybackSprintPolicy.kt",
 P+"playback/PlayerConnection.kt",
 P+"playback/MusicLabSectionPlayback.kt",
 P+"playback/StreamUrlCache.kt",
 P+"utils/InnerTubeXPlayer.kt",
 P+"ui/player/Player.kt",
 P+"ui/player/MiniPlayer.kt",
 P+"ui/player/ThumbnailSnapUtils.kt",
 P+"ui/component/CompilationScreen.kt",
 P+"ui/component/CoverSearchScreen.kt",
 P+"ui/component/MusicLabFastPath.kt",
 P+"discogs/CompilationTrackResolver.kt",
 ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
 assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected),"R61 protected module: "+protected
thumb=Path(P+"ui/player/Thumbnail.kt").read_text()
bridge=Path(P+"ui/component/CoverSwipeBridge.kt").read_text()
browser=Path(P+"ui/component/DiscogsDirectVersionBrowser.kt").read_text()
assert "R61VerifiedCoverArtwork" in thumb
assert "if (coverArtworkMode) return@LaunchedEffect" in thumb
assert "CoverSwipeBridge.isCoverArtwork" in thumb
assert "fun expectIncoming(" in bridge
assert "fun beginSelection(" in bridge
assert browser.index("CoverSwipeBridge.expectIncoming(sessionKey, selectedId)") < browser.index("connection.playNow(selectedItem)")
assert "CoverSwipeBridge.activate(sessionKey, selectedId)" in browser
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R61] exact Cover UI scope, all native audio/Compilation/R60 code untouched")
