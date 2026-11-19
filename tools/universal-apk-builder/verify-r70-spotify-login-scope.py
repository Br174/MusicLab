#!/usr/bin/env python3
"""R70: exact Spotify login UI delta only, preserving signed R69 baseline and Scudo."""
from pathlib import Path
import subprocess

BASE = "f1332f76a6e52fff040b7d8c2d1dd79eaaef3ea9"
UI = "app/src/main/kotlin/com/metrolist/music/ui/screens/SpotifyLoginScreen.kt"
EXPECTED = {
    UI,
    "cloudflare/music-intelligence/test/musiclab-r70-spotify-login.test.mjs",
    "scripts/codespaces-r70-autobuild.sh",
    "go.sh",
    "why.sh",
    "tools/universal-apk-builder/verify-r70-spotify-login-scope.py",
    "tools/universal-apk-builder/prepare-turbo-r70-33150-env.py",
    ".github/workflows/musiclab-r70-spotify-login-33150-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
actual = set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual == EXPECTED, "Scope mismatch: " + repr(sorted(actual ^ EXPECTED))
before = subprocess.check_output(["git","show",BASE + ":" + UI],text=True)
after = Path(UI).read_text()
assert before.count("cookieManager.removeAllCookies(null)") == 1
assert "cookieManager.removeAllCookies(null)" not in after
assert "cookieManager.flush()" not in after
assert "key(retryCount)" in after
assert "import androidx.compose.runtime.key" in after
assert "tokenFetchStarted.compareAndSet(false, true)" in after
assert "loadUrl(SpotifyAuth.LOGIN_URL)" in after
assert "extractAndFetchToken(" in after
for protected in (
    "app/src/main/kotlin/com/metrolist/spotify/SpotifyAuth.kt",
    "app/src/main/kotlin/com/metrolist/spotify/Spotify.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/utils/SpotifyTokenManager.kt",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected) == git("rev-parse","HEAD:"+protected),protected
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R70] PASS exact Spotify UI fix, protected player/search/token/auth unchanged")
