#!/usr/bin/env python3
"""R65: scoped Cover sort/date fix based on successfully signed R64."""
from pathlib import Path
import subprocess

BASE="9d173081d06071c36ac04d07db95995faaf2b0b7"
P="app/src/main/kotlin/com/metrolist/music/"
ALLOWED={
    P+"ui/component/DiscogsDirectVersionBrowser.kt",
    P+"ui/component/CoverVisibleSortPolicy.kt",
    P+"ui/component/CoverVisibleYearResolver.kt",
    P+"ui/component/CoverCardMetadata.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverVisibleSortPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r65-cover-sort-dates.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r65-33145-env.py",
    "tools/universal-apk-builder/verify-r65-cover-sort-scope.py",
    ".github/workflows/musiclab-r65-cover-sort-33145-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()

subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert changed==ALLOWED, "Unexpected/missing R65 changes: "+repr(sorted(changed ^ ALLOWED))
for protected in (
    P+"playback/MusicService.kt",
    P+"playback/PlayerConnection.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/MusicLabSwipeInputPolicy.kt",
    P+"playback/PlaybackSprintPolicy.kt",
    P+"ui/player/Thumbnail.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    P+"viewmodels/OnlineSearchViewModel.kt",
    P+"ui/component/CoverSwipeBridge.kt",
    P+"discogs/DiscogsClient.kt",
    P+"discogs/CompilationTrackResolver.kt",
    P+"ui/component/CompilationScreen.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected), "Protected: "+protected
source=Path(P+"ui/component/DiscogsDirectVersionBrowser.kt").read_text()
assert "CoverVisibleSortPolicy.sort(" in source and "CoverVisibleYearResolver.discover(" in source
assert "if (sortMode != value)" in source
assert "runSearch(forceRefresh = true)" in source
assert "sourceDiscoveryLoading && previousRoundRows.isNotEmpty()" in source
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R65] Cover-only sorting and bounded metadata enrichment; player, swipe, search untouched")
