#!/usr/bin/env python3
"""Exact source ownership proof for R31 Hit Parade date format repair."""
import subprocess
from pathlib import Path

BASE="6243a9ce7c497050b77cd1c780b73a2f69a01b68"
ALLOWED={
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeTest.kt",
 "tools/universal-apk-builder/prepare-turbo-r31-hitparade-33111-env.py",
 "tools/universal-apk-builder/verify-r31-hitparade-33111-scope.py",
 ".github/workflows/musiclab-r31-hitparade-33111-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed == ALLOWED, "R31 unexpected change: "+repr(sorted(changed.symmetric_difference(ALLOWED)))
protected=[
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for p in protected:
 left=subprocess.check_output(["git","rev-parse",BASE+":"+p],text=True).strip()
 right=subprocess.check_output(["git","rev-parse","HEAD:"+p],text=True).strip()
 assert left == right, "33110 protected component changed: "+p
src=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
for marker in ("validSong","tableRows","titleCol","artistCol","oldOrdered","top10Heading","chartHeading","MusicLabChartArtwork(","sameRecordingLabel"):
 assert marker in src, "Parser behavior missing: "+marker
test=Path("app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeTest.kt").read_text()
for marker in ("top50From1959","top10From1981","top10From1983","top200From2017","modernTop200From2026","numericMetadataCannotBecomeTitle"):
 assert marker in test, "Missing era-specific regression: "+marker
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R31] Scudo360 compatibility scope: PASS")
