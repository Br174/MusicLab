#!/usr/bin/env python3
"""R28 is an isolated Search-only LAB descended from the verified signed R25."""
import subprocess
BASE="c5cbf6d8f2c7c79f634d675c29e9e8d76d7d48d3"
allowed={
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalogTest.kt",
 "cloudflare/music-intelligence/test/turbo-r28-search-explore.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r28-search-explore-env.py",
 "tools/universal-apk-builder/verify-r28-search-explore-scope.py",
 ".github/workflows/musiclab-turbo-r28-search-explore-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed, "R28 forbidden changes: "+str(sorted(changes-allowed))
assert "app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt" in changes
for forbidden in ["app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/LocalSearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryPlaylistsScreen.kt",
 "app/src/main/kotlin/com/metrolist/spotify/Spotify.kt",
 "behaviors/musiclab-cover.json"]:
 assert forbidden not in changes, forbidden
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R28 SCOPE=PASS] only Search empty state; original signed R25 player, Cover, cycles, Library, production")
