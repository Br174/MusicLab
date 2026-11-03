#!/usr/bin/env python3
"""R57: prove changes only for compilation fast-page search and CI gates."""
import subprocess
from pathlib import Path
BASE = "c2b9b52e15dd58c01cf37a1762ea7fdd67eadc92"
P = "app/src/main/kotlin/com/metrolist/music/"
EXPECTED = {
  ".github/workflows/musiclab-r57-compilation-batch-filters-33137-apk.yml",
  P+"ui/component/CompilationScreen.kt",
  P+"discogs/DiscogsClient.kt",
  "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
  "cloudflare/music-intelligence/test/musiclab-r55-discogs-multiselect-progressive-audio.test.mjs",
  "cloudflare/music-intelligence/test/musiclab-r57-compilation-batch-results.test.mjs",
  "tools/universal-apk-builder/prepare-turbo-r57-33137-env.py",
  "tools/universal-apk-builder/verify-r57-33137-scope.py",
}
def git(*args):
  return subprocess.check_output(["git", *args], text=True).strip()
subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
actual = set(filter(None, git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual == EXPECTED, "R57 scope mismatch: " + repr(sorted(actual.symmetric_difference(EXPECTED)))
for untouched in (
  P+"playback/MusicService.kt",
  P+"playback/PlaybackSprintPolicy.kt",
  P+"playback/MusicLabSectionPlayback.kt",
  P+"ui/component/CompilationLightSearchPolicy.kt",
  P+"ui/component/CompilationNavigationBridge.kt",
  P+"ui/component/MusicLabCompilationSnapshot.kt",
  P+"ui/component/CoverSearchScreen.kt",
  P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
  P+"utils/InnerTubeXPlayer.kt",
  P+"ui/player/Player.kt",
  P+"ui/player/MiniPlayer.kt",
  ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
  assert git("rev-parse",BASE+":"+untouched)==git("rev-parse","HEAD:"+untouched), "Unauthorized change: "+untouched
ui = Path(P+"ui/component/CompilationScreen.kt").read_text()
assert 'val visibleItems = if (criteria.trackTitle != null)\n            pageResult.items' in ui
assert 'trackTitle = query.takeIf(String::isNotBlank)?.let' in ui
assert 'verifiedTrackIds[confirmed.id] = true' in ui
assert 'verificationJob?.cancel()' in ui
assert 'Brano non confermato' in ui
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R57] exact scoped batch search and filters-only repair, player and other sections untouched")
