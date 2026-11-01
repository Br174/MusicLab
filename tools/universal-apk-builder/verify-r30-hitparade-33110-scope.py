#!/usr/bin/env python3
"""R30 Hit Parade multi-era repair: source-isolated, no regression to 33109."""
from pathlib import Path
import subprocess

BASE = "984e6d0cde6cface67a21036858d98d2258e4218"
ALLOWED = {
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeTest.kt",
 "tools/universal-apk-builder/prepare-turbo-r30-hitparade-33110-env.py",
 "tools/universal-apk-builder/verify-r30-hitparade-33110-scope.py",
 ".github/workflows/musiclab-r30-hitparade-33110-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed == ALLOWED, "R30 unexpected/absent: " + repr(sorted(changed.symmetric_difference(ALLOWED)))
PROTECTED = [
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for p in PROTECTED:
 before=subprocess.check_output(["git","rev-parse", BASE+":"+p],text=True).strip()
 after=subprocess.check_output(["git","rev-parse","HEAD:"+p],text=True).strip()
 assert before==after, "Protected 33109 source changed: "+p
src=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
for item in ("hpw_$year.htm", "table.piccolo tr", "modernLink", "classicLink", "saturdayOf", "MusicLabChartArtwork(", "sameRecordingLabel"):
 assert item in src, "Missing historical feature: "+item
test=Path("app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeTest.kt").read_text()
for item in ("calendarUsesRealLegacyIndexFor1995And2010", "realStyleTop20AndTop100TableKeepExactArtistsAndRanks", "actual1980ListUsesTheRankedDiscSide"):
 assert item in test, "Missing regression: "+item
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R30 33110 SCOPE PASS] Exact 33109 base + unchanged player/artwork + historical eras")
