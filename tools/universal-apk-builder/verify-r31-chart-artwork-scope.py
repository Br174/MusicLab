#!/usr/bin/env python3
"""R31 approved country charts + standard artwork rows; R30 verified, Cover/player protected."""
import subprocess
BASE="75fac2793415d05f70ead109946ae2e1b46c487a"
allowed={
"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
"app/src/test/kotlin/com/metrolist/lastfm/LastFMResponseTest.kt",
"cloudflare/music-intelligence/test/turbo-r31-chart-artwork.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r31-chart-artwork-env.py",
"tools/universal-apk-builder/verify-r31-chart-artwork-scope.py",
".github/workflows/musiclab-turbo-r31-chart-artwork-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed <= allowed, "Unexpected changes: "+str(sorted(changed-allowed))
assert {"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt"} <= changed
for forbidden in [
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
"app/src/main/kotlin/com/metrolist/music/App.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt",
"behaviors/musiclab-cover.json"]:
 assert forbidden not in changed
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R31 SCOPE=PASS] Last.fm JSON and artwork only; player/Cover unchanged")
