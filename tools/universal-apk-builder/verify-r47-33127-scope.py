#!/usr/bin/env python3
"""R47 exact approved source delta from signed/verified R46 33126."""
from pathlib import Path
import subprocess

BASE="a077e7636e609f1f1e8a542153110e1f272006cd"
EXPECTED={
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraChartsTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r47-four-hitparade-categories.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r47-33127-env.py",
    "tools/universal-apk-builder/verify-r47-33127-scope.py",
    ".github/workflows/musiclab-r47-four-hitparade-charts-33127-apk.yml",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED,"Unauthorized R47 file changes: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r46-player-compilation-fallback-33126-apk.yml",
):
    assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path),path
old=subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"],text=True)
new=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
assert "MusicLabHistoricalHitParade.load(selected)" in new
assert "MusicLabHistoricalHitParade.load(selected)" in old
assert "MusicLabSectionPlayback.select(" in new
assert "MusicLabHistoricalChartModeMenu(selectedMode, onModeChange)" in new
assert "MusicLabSectionWorkCheckpoint.complete(context, \"hitparade\"" in new
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R47 SCUDO] source exact, weekly single parser/audio unchanged, R46 and signature protected")
