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

test("R70 Codespaces installs the Android 37.0 minor-versioned platform, not nonexistent 37",()=>{
  const builder=readFileSync("scripts/codespaces-r70-autobuild.sh","utf8");
  assert.ok(builder.includes("'platforms;android-37.0'"));
  assert.ok(builder.includes('platforms/android-37.0/android.jar'));
  assert.ok(!builder.includes("'platforms;android-37'"));
  assert.ok(builder.includes("compileSdk=37 uses 37.0"));
});

test("R70 Codespaces memory budget restores project Gradle settings on exit",()=>{
  const builder=readFileSync("scripts/codespaces-r70-autobuild.sh","utf8");
  const projectGradle=readFileSync("gradle.properties","utf8");
  assert.match(projectGradle,/org\.gradle\.jvmargs=-Xmx4096M/);
  assert.match(projectGradle,/org\.gradle\.parallel=true/);
  assert.ok(builder.includes('GRADLE_BACKUP="$(mktemp'));
  assert.ok(builder.includes('cp "$GRADLE_BACKUP" "$ROOT/gradle.properties"'));
  assert.ok(builder.includes("org.gradle.jvmargs=-Xmx2304m"));
  assert.ok(builder.includes("-XX:MaxMetaspaceSize=1g"));
  assert.ok(builder.includes("org.gradle.workers.max=1"));
  assert.ok(builder.includes("kotlin.compiler.execution.strategy=in-process"));
  assert.ok(builder.includes("org.gradle.parallel=false"));
  assert.ok(builder.includes('export GITHUB_SHA="$SHA"'));
  assert.ok(builder.includes('OOM_BEFORE="$(oom_kills)"'));
  assert.ok(builder.includes('OOM_AFTER="$(oom_kills)"'));
  assert.ok(builder.includes("SCUDO360_R40_PROMOTION=1 node"));
});
