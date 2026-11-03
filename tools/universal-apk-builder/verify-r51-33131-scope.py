#!/usr/bin/env python3
"""R51 exact user-approved delta, from immutable successful R50 33130."""
from pathlib import Path
import subprocess

BASE = "13fbb211816ec74ec3ad3928b3a597873e692b95"
EXPECTED = {
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsSearchMode.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationUnifiedStyleFilter.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
    "app/src/test/kotlin/com/metrolist/music/discogs/DiscogsSearchModeTest.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationUnifiedStyleFilterTest.kt",
    "cloudflare/music-intelligence/test/lab28-compilation-discogs-contract.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r51-compilation-album-modes.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r51-33131-env.py",
    "tools/universal-apk-builder/verify-r51-33131-scope.py",
    ".github/workflows/musiclab-r51-compilation-album-modes-33131-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
actual=set(git("diff", "--name-only", BASE, "HEAD").splitlines())
assert actual == EXPECTED, "R51 scope differs: " + repr(sorted(actual.symmetric_difference(EXPECTED)))
for file in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r50-hitparade-true-record-artwork-33130-apk.yml",
):
    assert git("rev-parse", BASE+":"+file) == git("rev-parse", "HEAD:"+file), file

screen = Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
api = Path("app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt").read_text()
assert 'label = "Categoria"' in screen and 'label = "Stile"' in screen
assert 'label = "Genere"' not in screen
assert "CompilationUnifiedStyleFilter.options(" in screen
assert 'mode = DiscogsSearchMode.COMPILATION' in screen
assert 'finalCriteria.copy(mode = DiscogsSearchMode.ALBUM)' in screen
assert 'DiscogsSearchMode.BOTH -> Unit' in api
assert 'DiscogsSearchMode.ALBUM ->' in api
assert 'DiscogsSearchMode.COMPILATION ->' in api
assert 'if (!mode.accepts(formats)) continue' in api
assert "DiscogsClient.getRelease(" in screen
assert "MusicLabSectionPlayback.select(" in screen
assert "MusicLabSectionWorkCheckpoint.standby(" in screen
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R51 SCOPE] Correct category routing and one Stile filter; R50 artwork, player, signing and archive preserved")
