#!/usr/bin/env python3
import subprocess
BASE="c5cbf6d8f2c7c79f634d675c29e9e8d76d7d48d3"
allowed={
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryPlaylistsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryMixScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/PlaylistSourcePolicy.kt",
"app/src/test/kotlin/com/metrolist/music/ui/screens/library/PlaylistSourcePolicyTest.kt",
"cloudflare/music-intelligence/test/turbo-r25-library-spotify-first.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r25-library-spotify-first-env.py",
"tools/universal-apk-builder/verify-r25-library-spotify-first-scope.py",
".github/workflows/musiclab-r25-library-spotify-first-apk.yml"}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed,"UNEXPECTED CHANGES: "+str(changes-allowed)
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R25 UI SCOPE PASS] Cover/player/DB/MADRE untouched")
