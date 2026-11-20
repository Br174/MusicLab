import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const ui = readFileSync(
  "app/src/main/kotlin/com/metrolist/music/ui/screens/SpotifyLoginScreen.kt", "utf8",
);
const oldTests = readFileSync(
  "cloudflare/music-intelligence/test/musiclab-r70-spotify-login.test.mjs", "utf8",
);

test("R71 has explicit reversible Android/desktop login mode, not forced global default", () => {
  assert.match(ui, /var useAndroidUserAgent by remember \{ mutableStateOf\(false\) \}/);
  assert.match(ui, /TextButton\(onClick = \{/);
  assert.match(ui, /useAndroidUserAgent = !useAndroidUserAgent/);
  assert.match(ui, /Text\(if \(useAndroidUserAgent\) "Desktop" else "Android"\)/);
  assert.match(ui, /settings\.userAgentString = if \(useAndroidUserAgent\) \{/);
  assert.match(ui, /WebSettings\.getDefaultUserAgent\(ctx\)/);
  assert.match(ui, /USER_AGENT_DESKTOP/);
});

test("R71 mode changes reconstruct WebView and reset only transient state", () => {
  assert.match(ui, /useAndroidUserAgent = !useAndroidUserAgent[\s\S]*?tokenFetchStarted\.set\(false\)[\s\S]*?retryCount\+\+/);
  assert.match(ui, /key\(retryCount\)\s*\{[\s\S]*?AndroidView\(/);
  assert.doesNotMatch(ui, /removeAllCookies\s*\(/);
  assert.doesNotMatch(ui, /removeSessionCookies\s*\(/);
  assert.doesNotMatch(ui, /removeAllWebViewData\s*\(/);
});

test("R71 preserves Spotify session/token flow; R70 safety contract is still runnable", () => {
  assert.match(ui, /cookieManager\.setAcceptCookie\(true\)/);
  assert.match(ui, /setAcceptThirdPartyCookies\(this, true\)/);
  assert.match(ui, /loadUrl\(SpotifyAuth\.LOGIN_URL\)/);
  assert.match(ui, /extractAndFetchToken\(/);
  assert.match(ui, /SpotifyAuth\.fetchAccessToken\(spDc, spKey\)/);
  assert.match(oldTests, /Spotify token exchange and navigation stay intact/);
});

test("R71 user-agent trial is explicitly not a Google OAuth security bypass", () => {
  assert.doesNotMatch(ui, /setWebContentsDebuggingEnabled\(true\)/);
  assert.doesNotMatch(ui, /handler\?\.proceed\(\)/);
  assert.match(ui, /super\.onReceivedSslError\(view, handler, error\)/);
});
