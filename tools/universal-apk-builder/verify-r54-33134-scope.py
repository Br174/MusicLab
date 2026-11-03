#!/usr/bin/env python3
"""R54 Discogs-only light search. Isolate from R53 player, Cover engine, R50 Hit Parade.

Build authorization: tests passed on Actions run 41685294848; this commit
requests the signed APK/ZIP without broadening source scope.
"""
from pathlib import Path
import subprocess

BASE="274ff9e6b9d3a2556923702c0cf9d28fe981bbe7"
P="app/src/main/kotlin/com/metrolist/music/"
EXPECTED={
    ".github/workflows/musiclab-r54-discogs-three-modes-cover-lite-33134-apk.yml",
    P+"discogs/DiscogsClient.kt",
    P+"ui/component/CompilationLightSearchPolicy.kt",
    P+"ui/component/CompilationScreen.kt",
    P+"ui/component/CompilationSearchSessionPolicy.kt",
    P+"ui/component/MusicLabCompilationSnapshot.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationLightSearchPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r45-manual-version-scroll-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r46-player-compilation-fallback.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r51-compilation-album-modes.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r52-discogs-tracklist-verified.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r53-manual-title-player-restored.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r54-discogs-light-cover-three-modes.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r54-33134-env.py",
    "tools/universal-apk-builder/verify-r54-33134-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()

subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert actual==EXPECTED, "Unauthorized R54 difference: "+repr(sorted(actual.symmetric_difference(EXPECTED)))

for untouched in (
    P+"playback/CoverPlaybackMemory.kt",
    P+"playback/PlayerConnection.kt",
    P+"playback/MusicService.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    P+"ui/menu/PlayerMenu.kt",
    P+"ui/menu/SongMenu.kt",
    P+"ui/menu/MusicLabIntelligenceMenu.kt",
    P+"ui/component/CoverSearchScreen.kt",
    P+"ui/component/AiCoverSearchEngine.kt",
    P+"ui/component/MusicLabCompilationCoverBridge.kt",
    P+"ui/component/CompilationNavigationBridge.kt",
    P+"discogs/DiscogsSearchMode.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+untouched)==git("rev-parse","HEAD:"+untouched), "Unexpected R54 mutation "+untouched

ui=Path(P+"ui/component/CompilationScreen.kt").read_text()
policy=Path(P+"ui/component/CompilationLightSearchPolicy.kt").read_text()
discogs=Path(P+"discogs/DiscogsClient.kt").read_text()
assert 'CompilationLookupMode.TITLE_ARTIST' in ui
assert 'CompilationLookupMode.entries.forEach' in ui
assert 'runSearch(CompilationSearchCriteria(' in ui
assert 'CompilationLightSearchPolicy.accepts(' in ui
assert 'release.tracks.none { track ->' in ui
assert 'trackTitle = criteria.trackTitle' in ui
assert 'includeSingles = criteria.lookupMode == CompilationLookupMode.COVER_LITE' in ui
assert 'if (!includeSingles && !mode.accepts(formats)) continue' in discogs
assert 'addQueryParameter("track", trackTitle.trim())' in discogs
assert 'originalArtistPresent(track, originalArtist)' in policy
assert 'alternateMarker.containsMatchIn(track.title)' in policy
assert 'CoverNavigationBridge' not in policy
assert 'selected = found.song.toMediaItem()' in ui
assert 'MusicLabCompilationCoverBridge.mediaItem(' not in ui
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R54 SCOPE] only isolated Discogs tracklist search; prior player/Cover/HitParade intact; signed v33134")
