#!/usr/bin/env python3
"""R32 exact scope and root-cause proof. Preserve 33111 player and all other screens."""
import subprocess
from pathlib import Path

BASE="c022c058175e57438db28d7b2c77b302510f1093"
fixtures={f"app/src/test/resources/hitparade/live-{y}.html" for y in (1959,1980,1981,1984,1995,2017,2020,2026)}
indices={f"app/src/test/resources/hitparade/index-{y}.html" for y in (1959,1984,1995,2017,2026)}
expected={
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeLiveFixtureTest.kt",
 "tools/universal-apk-builder/prepare-turbo-r32-hitparade-33112-env.py",
 "tools/universal-apk-builder/verify-r32-hitparade-33112-scope.py",
 ".github/workflows/musiclab-r32-hitparade-33112-apk.yml",
} | fixtures | indices
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed == expected, "Unexpected R32 scope: " +repr(sorted(changed.symmetric_difference(expected)))
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
 old=subprocess.check_output(["git","rev-parse",BASE+":"+p],text=True).strip()
 now=subprocess.check_output(["git","rev-parse","HEAD:"+p],text=True).strip()
 assert old==now,"Protected 33111 code changed: "+p
source=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
for marker in ["parseOriginalListSource","parseOriginalTableSource","matchLegacyHeading","saturdayOf","MusicLabChartArtwork(","sameRecordingLabel"]:
 assert marker in source, "Missing R32 behavior "+marker
test=Path("app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeLiveFixtureTest.kt").read_text()
for marker in ["actual1959Top50","actual1980Top50","preserveActual1981Top10","preserveActual1984Top10","preserveActual1995Top20","actual2017Top200","actual2020Top200","actual2026Top200","actualIndex2017"]:
 assert marker in test, "Missing R32 contract "+marker
for p in fixtures|indices:
 assert Path(p).stat().st_size>200, "Missing real-world fixture "+p
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R32] Immutable other screens/player and 13 real-world fixtures verified")
