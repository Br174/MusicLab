#!/usr/bin/env python3
"""Fail-closed protection for MusicLab signed R33, from verified 33112."""
from pathlib import Path
import subprocess

BASE="dd1a3a806fd7f81c908771b181a4c3df4f96cce9"
EXPECTED={
 ".github/workflows/musiclab-r33-hitparade-http-probe.yml",
 ".github/workflows/musiclab-r33-hitparade-live-http-test.yml",
 ".github/workflows/musiclab-r33-hitparade-33113-apk.yml",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeRealHttpTest.kt",
 "tools/universal-apk-builder/probe-hitparade-http-33113.py",
 "tools/universal-apk-builder/prepare-turbo-r33-hitparade-33113-env.py",
 "tools/universal-apk-builder/verify-r33-hitparade-33113-scope.py",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed == EXPECTED,"Unexpected/missing file changes: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
PROTECTED=[
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for path in PROTECTED:
 original=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
 current=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
 assert original==current, "33112 component modified: "+path
source=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
for marker in ('actualPage.select("li")', '.brano-dettagli-titolo', '.brano-dettagli-artista',
               '.rank h5', 'directWeekUrl', 'year in 1981..2017', 'MusicLabChartArtwork(', 'sameRecordingLabel'):
 assert marker in source,"Missing real-layout behavior: "+marker
live=Path("app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeRealHttpTest.kt").read_text()
for marker in ("live1959", "live1980", "live2018", "live2020", "live2026", "keepClassic1995"):
 assert marker in live,"Missing live HTTP regression: "+marker
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R33 33113] exact source scope, original player/artwork and real HTTP regressions: PASS")
