#!/usr/bin/env python3
"""MusicLab R48 exact user-authorized surface, inherited from signed 33127."""
from pathlib import Path
import subprocess

BASE="f9304adbf235dc76094bcd14206bba5fb7b926ff"
EXPECTED={
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoMemoryTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r36-fastpath-hitparade-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r47-four-hitparade-categories.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r48-hitparade-video-recovery.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r48-33128-env.py",
    "tools/universal-apk-builder/verify-r48-33128-scope.py",
    ".github/workflows/musiclab-r48-hitparade-video-recovery-33128-apk.yml",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED,"Unauthorized R48 files: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabManualVersionStore.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r47-four-hitparade-charts-33127-apk.yml",
):
    assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path),path

old=git("show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt")
new=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
marker="internal object MusicLabHistoricalHitParade {"
end="@OptIn(ExperimentalMaterial3Api::class)"
assert old.split(marker,1)[1].split(end,1)[0] == new.split(marker,1)[1].split(end,1)[0], "HPI weekly parser altered"
extra_old=git("show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt")
extra_new=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt").read_text()
assert extra_old.split(end,1)[0] == extra_new.split(end,1)[0], "HPI new four-chart parsers altered"
assert "MusicLabHistoricalHitParade.load(selected)" in new
assert "MusicLabHistoricalChartModeMenu(selectedMode, onModeChange)" in new
assert "MusicLabSectionWorkCheckpoint.complete(context, \"hitparade\"" in new
assert "if (!mode.isAlbum) MusicLabHitParadeVideoMenu" in extra_new
assert "MusicLabSectionPlayback.select(" in new and "MusicLabSectionPlayback.select(" in extra_new
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R48] exact scope: original historical parsers, playback, Scudo and signing untouched")
