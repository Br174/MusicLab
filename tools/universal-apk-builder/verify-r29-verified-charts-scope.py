#!/usr/bin/env python3
"""R29 verified charts only, exact R28 signed ancestor, preserve R25 audio/cover."""
import subprocess
BASE="fc03900b83a738991341104b1e9df94c94f5b7a5"
allowed={
"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
"innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
"app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalogTest.kt",
"cloudflare/music-intelligence/test/turbo-r29-verified-charts.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r29-verified-charts-env.py",
"tools/universal-apk-builder/verify-r29-verified-charts-scope.py",
".github/workflows/musiclab-turbo-r29-verified-charts-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed, "Out-of-scope R29 edits: "+str(sorted(changes-allowed))
for protected in [
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/playback/SpotifyYouTubeMapper.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/spotify/Spotify.kt",
"behaviors/musiclab-cover.json"]:
 assert protected not in changes,protected
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R29 SCOPE=PASS] independent chart data, R28 UI, R25 Cover/audio protected")
