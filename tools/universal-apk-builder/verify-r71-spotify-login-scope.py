#!/usr/bin/env python3
"""R71: exact isolated UI experiment, never mislabelled as Spotify playlist sync restoration."""
from pathlib import Path
import subprocess

BASE_R70 = "b6e9f86e64aed114e3706514143559c183e58a2b"
UI = "app/src/main/kotlin/com/metrolist/music/ui/screens/SpotifyLoginScreen.kt"
EXPECTED = {
    UI,
    "docs/MUSICLAB_R71_SPOTIFY_AUTH_RECOVERY.md",
    "cloudflare/music-intelligence/test/musiclab-r71-spotify-login.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r71-33151-env.py",
    "tools/universal-apk-builder/verify-r71-spotify-login-scope.py",
    "scripts/codespaces-r71-autobuild.sh",
    "go-r71.sh",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

subprocess.run(["git", "merge-base", "--is-ancestor", BASE_R70, "HEAD"], check=True)
actual = set(filter(None, git("diff", "--name-only", BASE_R70, "HEAD").splitlines()))
assert actual == EXPECTED, "Unexpected R71 changes: " + repr(sorted(actual ^ EXPECTED))
before = subprocess.check_output(["git", "show", BASE_R70 + ":" + UI], text=True)
after = Path(UI).read_text()
assert before.count("settings.userAgentString = USER_AGENT_DESKTOP") == 1
assert "settings.userAgentString = if (useAndroidUserAgent)" in after
assert "WebSettings.getDefaultUserAgent(ctx)" in after
assert "var useAndroidUserAgent by remember { mutableStateOf(false) }" in after
assert "useAndroidUserAgent = !useAndroidUserAgent" in after
assert "key(retryCount)" in after
assert "tokenFetchStarted.set(false)" in after
assert "cookieManager.removeAllCookies(" not in after
assert "cookieManager.removeSessionCookies(" not in after
assert "setAcceptThirdPartyCookies(this, true)" in after
assert "loadUrl(SpotifyAuth.LOGIN_URL)" in after
assert "SpotifyAuth.fetchAccessToken(spDc, spKey)" in after
assert "super.onReceivedSslError(view, handler, error)" in after
for protected in (
    "app/src/main/kotlin/com/metrolist/spotify/SpotifyAuth.kt",
    "app/src/main/kotlin/com/metrolist/spotify/Spotify.kt",
    "app/src/main/kotlin/com/metrolist/music/utils/SpotifyTokenManager.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/integrations/SpotifySettings.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse", BASE_R70 + ":" + protected) == git("rev-parse", "HEAD:" + protected), protected
subprocess.run(["git", "diff", "--check", BASE_R70, "HEAD"], check=True)
print("[R71] PASS exact login UA A/B scope; old playlist and player source untouched")
