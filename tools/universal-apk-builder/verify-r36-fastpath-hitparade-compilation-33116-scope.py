#!/usr/bin/env python3
"""MusicLab 33116 strict safe LAB scope, immutable 33115 historical source."""
from pathlib import Path
import subprocess

BASE="005ea75f1dffbae072ef52b91e7a200185d32a08"
EXPECTED={
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicyTest.kt",
 "cloudflare/music-intelligence/test/musiclab-r36-fastpath-hitparade-compilation.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r36-fastpath-33116-env.py",
 "tools/universal-apk-builder/verify-r36-fastpath-hitparade-compilation-33116-scope.py",
 ".github/workflows/musiclab-r36-fastpath-hitparade-compilation-33116-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed==EXPECTED,"R36 source changed outside planned scope: "+repr(
    sorted(changed.symmetric_difference(EXPECTED)))

PROTECTED=[
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt",
 "app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSwipeBridge.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for path in PROTECTED:
 old=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
 new=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
 assert old==new,"Protected production/cover/audio file modified: "+path

historical="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"
old=subprocess.check_output(["git","show",BASE+":"+historical],text=True)
new=Path(historical).read_text()
marker="@OptIn(ExperimentalMaterial3Api::class)"
start="internal data class HistoricalHit("
assert old[old.index(start):old.index(marker)]==new[new.index(start):new.index(marker)],(
  "STOP: Hit Parade HTML/weekly historic chart loader was modified")
assert "MusicLabFastHitArtwork(MusicLabChartTrack(" in new
assert "MusicLabFastPath.find(song.title, song.artist)" in new

comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
for must in (
 "DiscogsClient.searchCompilations(",
 "model = summary.coverUrl ?: summary.thumbnailUrl",
 "model = detail.coverUrl",
 "CompilationTrackResolver.isHardCompatible",
 "CompilationTrackResolver.resolveTrack(track, current.videos)",
 "playerConnection?.addToQueue(item)",
 "MusicLabFastPath.find(",
 "MusicLabFastPathPolicy.mayContinueCompilation",
):
 assert must in comp,"Compilation preservation/acceleration contract missing: "+must

fast=Path("app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt").read_text()
assert "YouTube.searchSummary(" in fast
assert "YouTube.SearchFilter.FILTER_SONG" in fast
assert "MusicLabChartArtwork(track)" in fast, "Fallback artwork lost"
assert "flights.computeIfAbsent" in fast, "Duplicate lookup protection lost"
test=Path("app/src/test/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicyTest.kt").read_text()
assert test.count("@Test")>=4
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB 33116 PASS] Historical HTML identical; Cover, Discogs originals, audio unchanged")
