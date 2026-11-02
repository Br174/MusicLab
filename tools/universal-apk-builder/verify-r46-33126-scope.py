#!/usr/bin/env python3
"""MotorLab R46: exact approved change scope from signed/verified R45 33125."""
from pathlib import Path
import subprocess

BASE = "7eeda188e6ac5fab751f502dd95d5bb8b30b3e22"
EXPECTED = {
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationR46PlayerFallbackPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r39-compilation-legacy-search.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r45-manual-version-scroll-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r46-player-compilation-fallback.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r46-33126-env.py",
    "tools/universal-apk-builder/verify-r46-33126-scope.py",
    ".github/workflows/musiclab-r46-player-compilation-fallback-33126-apk.yml",
}
def git(*args): return subprocess.check_output(["git", *args], text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED, "Unapproved R46 source delta: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r45-manual-version-scroll-compilation-33125-apk.yml",
):
    assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path),path
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
assert "query = criteria.query" in comp
assert "CompilationFallbackQueryPolicy.query" in comp
assert "playerRequest != null" in comp
assert "trackTitle = null, trackArtist = null" in comp
assert "Riproduci tutto per brani" not in comp
print("[R46 SCUDO] isolated R45 descendant, player audio/signing unchanged, approved exact fixes")
