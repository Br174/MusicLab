#!/usr/bin/env python3
import subprocess

BASE="b524901764dd04a57c8ecef2ae2f764e9b5619e0"
paths={
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt",
".github/workflows/musiclab-r26-chart-artwork-insets-33106-apk.yml",
"tools/universal-apk-builder/prepare-turbo-r26-chart-artwork-33106-env.py",
"tools/universal-apk-builder/verify-r26-chart-artwork-33106-scope.py",
"cloudflare/music-intelligence/test/turbo-r26-chart-artwork-insets.test.mjs",
"cloudflare/music-intelligence/test/turbo-r31-chart-artwork.test.mjs",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changes == paths, "R26 unexpected/absent files: "+repr(sorted(changes.symmetric_difference(paths)))
preserve=[
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt",
"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
]
for path in preserve:
    old=subprocess.check_output(["git","rev-parse",f"{BASE}:{path}"],text=True).strip()
    now=subprocess.check_output(["git","rev-parse",f"HEAD:{path}"],text=True).strip()
    assert now==old, "R26 must preserve "+path
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[33106 SCOPE PASS] Charts artwork/captions/player insets only; untouched player and Spotify First")
