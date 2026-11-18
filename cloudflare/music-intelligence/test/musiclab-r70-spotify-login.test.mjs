import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const ui=readFileSync("app/src/main/kotlin/com/metrolist/music/ui/screens/SpotifyLoginScreen.kt","utf8");

test("Spotify WebView login does not race a global asynchronous cookie purge",()=>{
  assert.doesNotMatch(ui,/removeAllCookies\s*\(/);
  assert.doesNotMatch(ui,/removeSessionCookies\s*\(/);
  assert.match(ui,/cookieManager\.setAcceptCookie\(true\)/);
  assert.match(ui,/setAcceptThirdPartyCookies\(this, true\)/);
});
test("retry creates a fresh WebView instead of only resetting overlay state",()=>{
  assert.match(ui,/import androidx\.compose\.runtime\.key/);
  assert.match(ui,/key\(retryCount\)\s*\{[\s\S]*?AndroidView\s*\(/);
  assert.match(ui,/retryCount\+\+/);
  assert.match(ui,/tokenFetchStarted\.set\(false\)/);
});
test("Spotify token exchange and navigation stay intact",()=>{
  assert.match(ui,/tokenFetchStarted\.compareAndSet\(false, true\)/);
  assert.match(ui,/loadUrl\(SpotifyAuth\.LOGIN_URL\)/);
  assert.match(ui,/SpotifyAuth\.fetchAccessToken\(spDc, spKey\)/);
  assert.match(ui,/navController\.navigateUp\(\)/);
});
