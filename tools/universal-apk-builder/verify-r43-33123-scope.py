#!/usr/bin/env python3
"""MotorLab MusicLab R43: exact Compilation-only Hit Parade FastPath scope."""
from pathlib import Path
import subprocess

BASE="7ffd106af1761a2a445ecf11c3ecb36c9df606bb"
EXPECTED={
  "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicyTest.kt",
  "cloudflare/music-intelligence/test/musiclab-r36-fastpath-hitparade-compilation.test.mjs",
  "cloudflare/music-intelligence/test/musiclab-r43-compilation-hitparade-prewarm.test.mjs",
  "tools/universal-apk-builder/prepare-turbo-r43-33123-env.py",
  "tools/universal-apk-builder/verify-r43-33123-scope.py",
  ".github/workflows/musiclab-r43-compilation-prewarm-33123-apk.yml",
}
PROTECTED=[
  "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
  "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
  "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
  "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
  "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
  "app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
  ".motorlab/active-family.json",
  "tools/universal-apk-builder/build-apk.sh",
]
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED, "R43 scope mismatch: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for p in PROTECTED:
    assert git("rev-parse",BASE+":"+p)==git("rev-parse","HEAD:"+p), "Protected: "+p
file="app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"
old=subprocess.check_output(["git","show",BASE+":"+file],text=True)
new=Path(file).read_text()
def load(s): return s.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
assert load(old)==load(new), "R39 Discogs q-mode must not be changed"
assert 'onVisibleTrack(index)' in new and 'LaunchedEffect(detail.id, index)' in new
assert 'Riproduci tutto per brani' not in new and 'resolveAllAndPlay' not in new
assert "MusicLabSectionPlayback.select(" in new
warm=new.split("suspend fun warmVisibleTrack(",1)[1].split("// Keep the original first-two head start",1)[0]
assert 'visibleWarmGate.withPermit' in warm
assert 'MusicLabFastPath.find(' in warm
assert 'isHardCompatible' in warm
assert 'playQueue(' not in warm and 'playNow(' not in warm
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R43 SCUDO360] scoped Compilation-only viewport prewarm, no autoplay, source unchanged, signing preserved")
