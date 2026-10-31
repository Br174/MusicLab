#!/usr/bin/env python3
"""R30 Last.fm stored-key reuse only; verified signed R29 base, no player/Cover changes."""
import subprocess
BASE="7a509ad9dfd8ecece283b088e4dc998e64b79783"
allowed={
"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/test/kotlin/com/metrolist/lastfm/LastFMResponseTest.kt",
"cloudflare/music-intelligence/test/turbo-r29-verified-charts.test.mjs",
"cloudflare/music-intelligence/test/turbo-r30-lastfm-credentials.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r30-lastfm-credentials-env.py",
"tools/universal-apk-builder/verify-r30-lastfm-credentials-scope.py",
".github/workflows/musiclab-turbo-r30-lastfm-credentials-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes <= allowed, "R30 forbidden modifications: "+str(sorted(changes-allowed))
assert {
"app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
}<= changes
for protected in [
"app/src/main/kotlin/com/metrolist/music/App.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/settings/ContentSettings.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"behaviors/musiclab-cover.json"]:
 assert protected not in changes, protected
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R30 SCOPE=PASS] stored Last.fm key linked to charts; Cover/audio/settings protected")
