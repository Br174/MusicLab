#!/usr/bin/env python3
"""R50 exact approved HPI artwork and presentation delta from successful R49 33129."""
from pathlib import Path
import subprocess

BASE="c4bd2643f11e6986bda87dd9264efc070d8cf931"
EXPECTED={
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeDiscArtworkTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r36-fastpath-hitparade-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r43-compilation-hitparade-prewarm.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r47-four-hitparade-categories.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r50-hitparade-disc-cover.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r50-33130-env.py",
    "tools/universal-apk-builder/verify-r50-33130-scope.py",
    ".github/workflows/musiclab-r50-hitparade-true-record-artwork-33130-apk.yml",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed == EXPECTED, "Unauthorized R50 files: "+repr(sorted(changed.symmetric_difference(EXPECTED)))

protected=(
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabManualVersionStore.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r49-hitparade-header-date-33129-apk.yml",
)
for path in protected:
    assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path),path

weekly="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"
extra="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt"
w_old=git("show",BASE+":"+weekly)
w_new=Path(weekly).read_text()
anchor="internal object MusicLabHistoricalHitParade {"
end="@OptIn(ExperimentalMaterial3Api::class)"
assert w_old.split(anchor,1)[1].split(end,1)[0] == w_new.split(anchor,1)[1].split(end,1)[0], "Original HPI weekly parser changed"
e_old=git("show",BASE+":"+extra)
e_new=Path(extra).read_text()
start="internal fun parseAnnualSingles("
assert (e_old.split(start,1)[1].split("    fun loadSaved(",1)[0].strip() ==
        e_new.split(start,1)[1].split("    /**\n     * Older on-device R47",1)[0].strip()), "HPI source parsers changed"
assert 'MusicLabHistoricalHitParade.load(selected)' in w_new
assert 'MusicLabHistoricalHeaderTitle(' in w_new and 'MusicLabHistoricalHeaderTitle(' in e_new
assert 'MusicLabHitParadeVideoMenu' in w_new and 'MusicLabHitParadeVideoMenu' in e_new
assert 'MusicLabSectionPlayback.select(' in w_new and 'MusicLabSectionPlayback.select(' in e_new
assert 'MusicLabHitParadeDiscCover(' in w_new and 'MusicLabHitParadeDiscCover(' in e_new
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R50 SCOPE] original chart parsers, audio path, player surfaces, signature, R49 UI and R48 manual video preserved")
