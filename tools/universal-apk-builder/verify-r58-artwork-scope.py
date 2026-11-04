#!/usr/bin/env python3
"""LAB58: exact safe artwork-only delta from signed R57 active-family source."""
import subprocess
from pathlib import Path

BASE = "b17409a7eb478a9851120791ab54ae8ffd4ac471"
root = "app/src/main/kotlin/com/metrolist/music/"
expected = {
    root+"ui/screens/search/MusicLabLightArtwork.kt",
    root+"ui/screens/search/MusicLabArtworkSearchDialog.kt",
    root+"ui/screens/search/MusicLabCustomCoverArtwork.kt",
    root+"ui/screens/search/MusicLabChartArtwork.kt",
    root+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    root+"ui/screens/search/MusicLabChartScreen.kt",
    root+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    root+"ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    root+"ui/screens/search/OnlineSearchResult.kt",
    root+"ui/component/Items.kt",
    root+"ui/component/CoverSearchScreen.kt",
    "tools/universal-apk-builder/prepare-turbo-r58-33138-env.py",
    "tools/universal-apk-builder/verify-r58-artwork-scope.py",
    "cloudflare/music-intelligence/test/musiclab-r58-light-artwork.test.mjs",
    ".github/workflows/musiclab-r58-light-artwork-33138-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
changed = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
assert changed == expected, "Unexpected or missing LAB58 paths: " + repr(sorted(changed ^ expected))
for preserved in (
    root+"playback/MusicService.kt",
    root+"playback/MusicLabSectionPlayback.kt",
    root+"playback/PlaybackSprintPolicy.kt",
    root+"utils/InnerTubeXPlayer.kt",
    root+"ui/player/Player.kt",
    root+"ui/player/MiniPlayer.kt",
    root+"viewmodels/OnlineSearchViewModel.kt",
    root+"discogs/DiscogsClient.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse", BASE+":"+preserved) == git("rev-parse", "HEAD:"+preserved), preserved
assert "UAB_UPDATE_FAMILY_VERSION_CODE" in Path("tools/universal-apk-builder/prepare-turbo-r58-33138-env.py").read_text()
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[LAB58] Exact bounded artwork-only change, native players and search engine untouched")
