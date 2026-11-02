#!/usr/bin/env python3
"""R39 guarded selective restore: R36 broad Compilation search on exact R38 verified head."""
from pathlib import Path
import subprocess

BASE="a1a1461fd8a57c3d49e8cc4107c739fde66a1e86"
R36="f4d5cc500a6fe94103306f627acb1fa4084b79b8"
EXPECTED={
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r39-legacy-search-33119-env.py",
    "tools/universal-apk-builder/verify-r39-legacy-search-33119-scope.py",
    ".github/workflows/musiclab-r39-compilation-legacy-search-33119-apk.yml",
}
PROTECTED=[
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    ".motorlab/active-family.json",
    "tools/universal-apk-builder/build-apk.sh",
]
run=lambda *args: subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(run("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED, "STOP UNAPPROVED R39 FILES: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in PROTECTED:
    assert run("rev-parse",BASE+":"+path)==run("rev-parse","HEAD:"+path), "STOP protected source changed: "+path
comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
previous=subprocess.check_output(["git","show",R36+":app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"],text=True)
required=(
    'query = criteria.query,',
    'perPage = if (criteria.language == null) 100 else 25,',
)
old_loading=previous.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
new_loading=comp.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
for clause in required:
    assert clause in old_loading and clause in new_loading, "R36 exact search clause missing: "+clause
assert 'trackTitle = criteria.trackTitle' not in new_loading
assert 'verifyContainingTrack(' not in comp
assert 'CompilationTrackMatchPolicy.matches(' not in comp
assert 'results = if (replaceResults) {\n            visibleItems' in new_loading
assert 'CompilationSearchTitlePolicy.clean(target.title)' in comp
assert 'trackTitle = null, trackArtist = null' in comp
assert 'if (generation != searchGeneration)' in comp
assert 'context.dataStore.data.first()[DiscogsTokenKey]' in comp
assert 'CompilationSearchHistoryStore.save(context, criteria)' in comp
assert 'runSearch(previous)' in comp
assert 'MusicLabFastPath.find(' in comp
assert 'CompilationTrackResolver.resolveTrack(track, current.videos)' in comp
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R39 PASS] Exact broad R36 Discogs q search and 100/25 page size restored; R38 and protected modules preserved")
