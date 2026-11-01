#!/usr/bin/env python3
"""R29 narrow compatibility gate: 33108 artwork, playback and signing are immutable."""
import subprocess
from pathlib import Path

BASE = "6d238db557a0e0de593559c129c6e5d1b58ce3b0"
ALLOWED = {
  "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParadeTest.kt",
  "tools/universal-apk-builder/prepare-turbo-r29-hitparade-33109-env.py",
  "tools/universal-apk-builder/verify-r29-hitparade-33109-scope.py",
  ".github/workflows/musiclab-r29-hitparade-33109-apk.yml",
}
subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
changed = set(subprocess.check_output(
  ["git", "diff", "--name-only", BASE, "HEAD"], text=True).splitlines())
assert changed == ALLOWED, "Unexpected/absent change: " + repr(sorted(changed.symmetric_difference(ALLOWED)))
protected = [
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreCatalog.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt",
  "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
  "tools/universal-apk-builder/build-apk.sh",
  ".motorlab/active-family.json",
]
for file in protected:
  previous = subprocess.check_output(["git", "rev-parse", BASE + ":" + file], text=True).strip()
  current = subprocess.check_output(["git", "rev-parse", "HEAD:" + file], text=True).strip()
  assert previous == current, "Protected 33108 code changed: " + file
ui = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt").read_text()
assert all(text in ui for text in ('ExploreTabChip("Classifiche"', 'ExploreTabChip("Generi musicali"', 'ExploreTabChip("Hit Parade"'))
assert 'navController.navigate("musiclab_hit_parade")' in ui
nav = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt").read_text()
assert nav.count('composable("musiclab_hit_parade")') == 1
historical = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
for marker in ("weeksFromIndex", "parseSongs", "sameRecordingLabel", "MusicLabChartArtwork(", "DatePickerDialog"):
  assert marker in historical, "Missing Hit Parade behavior " + marker
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[HIT PARADE R29 33109] Scope and 33108 protected player/artwork PASS")
