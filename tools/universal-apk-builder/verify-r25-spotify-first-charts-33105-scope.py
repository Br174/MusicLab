#!/usr/bin/env python3
import subprocess
BASE="2d56c742c29f0dfb67f42fefd56cfaa8a6f8b4b6"
RESTORED="511ef3e9cc9a4640d230acf7678df79e68e21324"
allow=["app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt","innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt","app/src/test/kotlin/com/metrolist/lastfm/LastFMResponseTest.kt","app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalogTest.kt","cloudflare/music-intelligence/test/turbo-r28-search-explore.test.mjs","cloudflare/music-intelligence/test/turbo-r29-verified-charts.test.mjs","cloudflare/music-intelligence/test/turbo-r30-lastfm-credentials.test.mjs","cloudflare/music-intelligence/test/turbo-r31-chart-artwork.test.mjs",".github/workflows/musiclab-r25-spotify-first-charts-33105-apk.yml","tools/universal-apk-builder/prepare-turbo-r25-spotify-first-charts-33105-env.py","tools/universal-apk-builder/verify-r25-spotify-first-charts-33105-scope.py","cloudflare/music-intelligence/test/turbo-r25-spotify-first-charts-restored.test.mjs"]
changes=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
assert changes == set(allow), "Unexpected or missing changes: "+str(sorted(changes.symmetric_difference(set(allow))))
for protected in [
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryPlaylistsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryMixScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/PlaylistSourcePolicy.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
]: assert protected not in changes,protected
for p in ["app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt","app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt","innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt","app/src/test/kotlin/com/metrolist/lastfm/LastFMResponseTest.kt","app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalogTest.kt","cloudflare/music-intelligence/test/turbo-r28-search-explore.test.mjs","cloudflare/music-intelligence/test/turbo-r29-verified-charts.test.mjs","cloudflare/music-intelligence/test/turbo-r30-lastfm-credentials.test.mjs","cloudflare/music-intelligence/test/turbo-r31-chart-artwork.test.mjs"]:
    actual=subprocess.check_output(["git","rev-parse","HEAD:"+p],text=True).strip()
    canonical=subprocess.check_output(["git","rev-parse",RESTORED+":"+p],text=True).strip()
    assert actual==canonical,"R31 source mismatch: "+p
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[33105 SCOPE PASS] R31 original charts + untouched R25 Cover/audio and Spotify First")
