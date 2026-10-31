#!/usr/bin/env python3
"""R27 exact-source compatibility gate: no player, packaging or chart regressions."""
import subprocess

BASE = "f8481272b635cc72d66ac76852cda29fe3ce0eaf"
paths = {
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt",
    ".github/workflows/musiclab-r27-artwork-coverage-33107-apk.yml",
    "tools/universal-apk-builder/prepare-turbo-r27-artwork-33107-env.py",
    "tools/universal-apk-builder/verify-r27-artwork-33107-scope.py",
    "cloudflare/music-intelligence/test/turbo-r27-artwork-coverage-playback.test.mjs",
}
subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
changed = set(subprocess.check_output(
    ["git", "diff", "--name-only", BASE, "HEAD"], text=True).splitlines())
assert changed == paths, "R27 unexpected/absent paths: " + repr(sorted(changed.symmetric_difference(paths)))
preserve = [
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/SpotifyYouTubeMapper.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/SearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
    "tools/universal-apk-builder/build-apk.sh",
]
for path in preserve:
    old = subprocess.check_output(["git", "rev-parse", BASE + ":" + path], text=True).strip()
    new = subprocess.check_output(["git", "rev-parse", "HEAD:" + path], text=True).strip()
    assert new == old, "R27 protected file changed: " + path
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[MUSICLAB 33107 SCOPE PASS] Only chart/genre artwork, verified playback lookup and isolated CI")
