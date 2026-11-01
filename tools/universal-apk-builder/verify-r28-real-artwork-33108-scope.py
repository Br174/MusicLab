#!/usr/bin/env python3
"""R28 artwork-only change scope; preserve player, search, genres and app identity."""
import subprocess
BASE="317c5bc5f4240a3bfab719f136ffcabe14735e7c"
allowed={
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
    "cloudflare/music-intelligence/test/turbo-r28-real-chart-album-artwork.test.mjs",
    ".github/workflows/musiclab-r28-real-chart-artwork-33108-apk.yml",
    "tools/universal-apk-builder/prepare-turbo-r28-real-artwork-33108-env.py",
    "tools/universal-apk-builder/verify-r28-real-artwork-33108-scope.py",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed == allowed, "R28 unexpected/absent changes: "+repr(sorted(changed.symmetric_difference(allowed)))
protected=[
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/SpotifyYouTubeMapper.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt",
    "app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt",
    "app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
    "tools/universal-apk-builder/build-apk.sh",
]
for path in protected:
    old=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
    now=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
    assert old==now,"R28 protected source changed: "+path
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[33108 SCOPE PASS] Last.fm placeholder rejection + same-track catalogue art; player and genre code untouched")
