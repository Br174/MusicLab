#!/usr/bin/env python3
"""Approved R52 exact functional delta from successful R51; no hidden audio refactor."""
from pathlib import Path
import subprocess

BASE="0b0778c51b2799eb7cf6a73a64a3f9c9de88f4c2"
EXPECTED={
    ".github/workflows/musiclab-r52-discogs-tracklist-verified-33132-apk.yml",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationTitleOnlyPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationCoverBridge.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationTitleOnlyPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r45-manual-version-scroll-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r51-compilation-album-modes.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r52-discogs-tracklist-verified.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r52-33132-env.py",
    "tools/universal-apk-builder/verify-r52-33132-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert actual==EXPECTED,"Unapproved R52 files: "+repr(sorted(actual.symmetric_difference(EXPECTED)))

for p in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsSearchMode.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r51-compilation-album-modes-33131-apk.yml",
):
    assert git("rev-parse",BASE+":"+p)==git("rev-parse","HEAD:"+p),p

ui=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
api=Path("app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt").read_text()
cover=Path("app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt").read_text()
assert 'onClick = { runSearch() }' in ui, "Cerca must remain literal"
assert 'Text("Titolo")' in ui and 'trackTitle = cleaned' in ui
assert 'verifyIndexedTrackCandidates(' in ui
assert 'CompilationTitleOnlyPolicy.selectedTrackIndex(' in ui
assert 'detailListState.scrollToItem(highlighted + 2)' in ui
assert 'addQueryParameter("track", trackTitle.trim())' in api
assert 'addQueryParameter("q", it)' in api
assert 'MusicLabCompilationCoverBridge.currentPlayerArtwork' in cover
assert 'MusicLabHitParadeDiscArtwork.currentPlayerArtwork' in cover
assert 'MusicLabSectionPlayback.select(' in ui
assert 'CompilationTrackResolver.resolveTrack(' in ui
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R52 SCOPE] R51 completed baseline preserved; Discogs track-index, title-only button, true release cover and non-playing highlight approved")
