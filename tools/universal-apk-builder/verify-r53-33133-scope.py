#!/usr/bin/env python3
"""R53 isolated, exact MusicLab fix after R52 with original R51 player restored."""
from pathlib import Path
import subprocess

R52="e3459f703a7e8786cdad878620ef6e7ee38e7ec2"
R51="0b0778c51b2799eb7cf6a73a64a3f9c9de88f4c2"
APP="app/src/main/kotlin/com/metrolist/music/"
EXPECTED={
    ".github/workflows/musiclab-r53-manual-search-player-restore-33133-apk.yml",
    APP+"playback/CoverPlaybackMemory.kt",
    APP+"ui/component/CompilationScreen.kt",
    APP+"ui/component/CompilationTitleOnlyPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationTitleOnlyPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r45-manual-version-scroll-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r46-player-compilation-fallback.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r51-compilation-album-modes.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r52-discogs-tracklist-verified.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r53-manual-title-player-restored.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r53-33133-env.py",
    "tools/universal-apk-builder/verify-r53-33133-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()

subprocess.run(["git","merge-base","--is-ancestor",R52,"HEAD"],check=True)
actual=set(git("diff","--name-only",R52,"HEAD").splitlines())
assert actual==EXPECTED, "R53 scope differs: "+repr(sorted(actual.symmetric_difference(EXPECTED)))

# THIS is the requested selective player rollback. Never regress the R50
# historical Hit Parade artwork, audio engine, search API or R52 tracklist.
assert git("rev-parse","HEAD:"+APP+"playback/CoverPlaybackMemory.kt")==git(
    "rev-parse",R51+":"+APP+"playback/CoverPlaybackMemory.kt"),"R51 player artwork metadata not exactly restored"
for file in (
    APP+"playback/MusicLabSectionPlayback.kt",
    APP+"playback/PlayerConnection.kt",
    APP+"playback/MusicService.kt",
    APP+"ui/player/MiniPlayer.kt",
    APP+"ui/player/Player.kt",
    APP+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    APP+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    APP+"ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    APP+"ui/component/MusicLabCompilationCoverBridge.kt",
    APP+"discogs/DiscogsClient.kt",
    APP+"discogs/DiscogsSearchMode.kt",
    APP+"discogs/CompilationTrackMatchPolicy.kt",
    APP+"ui/component/MusicLabCompilationSnapshot.kt",
    APP+"ui/component/CompilationNavigationBridge.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r52-discogs-tracklist-verified-33132-apk.yml",
):
    assert git("rev-parse",R52+":"+file)==git("rev-parse","HEAD:"+file),"Unapproved change: "+file

ui=Path(APP+"ui/component/CompilationScreen.kt").read_text()
title=Path(APP+"ui/component/CompilationTitleOnlyPolicy.kt").read_text()
assert 'query = target.title // preserve EXACT label from the locandina' in ui
nav=ui.split('LaunchedEffect(trackRequest?.requestId)',1)[1].split('fun loadNextPage()',1)[0]
assert 'runSearch(' not in nav and 'CompilationFallbackQueryPolicy.query' not in nav
assert 'if (trackRequest != null) return@LaunchedEffect' in ui
assert 'onClick = { runSearch() }' in ui
assert 'Text("Titolo")' in ui
assert 'CompilationTitleOnlyPolicy.title(' in ui
assert 'query = cleaned' in ui and 'trackTitle = cleaned' in ui
assert 'mode = DiscogsSearchMode.COMPILATION' in ui
assert 'label = ""' in ui and 'if (label.isBlank()) selected' in ui
assert 'selected = found.song.toMediaItem()' in ui
assert 'resolveTrack(trackIndex, current)?.song?.toMediaItem()' in ui
assert 'MusicLabCompilationCoverBridge.mediaItem(' not in ui
assert 'val alternative = criteria.copy(query = fallback)' not in ui
assert 'finalCriteria.copy(mode = DiscogsSearchMode.ALBUM)' not in ui
assert 'matchesKnownArtist' in title
assert 'detailListState.scrollToItem(highlighted + 2)' in ui
subprocess.run(["git","diff","--check",R52,"HEAD"],check=True)
print("[R53 SCOPE] exact R51 player restored; R52 Discogs, Hit Parade, playlists preserved; manual title and short category only")
