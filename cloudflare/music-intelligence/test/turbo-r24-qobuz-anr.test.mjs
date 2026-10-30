import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const service = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
const cover = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
function section(start,end) {
  const a=service.indexOf(start), b=service.indexOf(end,a+start.length);
  assert.ok(a>=0 && b>a,'missing '+start);
  return service.slice(a,b);
}
test('R24 initial Qobuz overrides are sourced from startupPrefs without four main-thread DataStore reads',()=>{
 const init=section('// Watch for Qobuz source-selection changes.','var isFirstQobuzEmit = true');
 assert.match(init,/applyQobuzEndpointOverrides\(/);
 for (const key of ['QobuzSquidEndpointKey','QobuzKennyEndpointKey','QobuzTryptEndpointKey','QobuzJumoEndpointKey']) {
   assert.ok(init.includes('startupPrefs!!['+key+']'), key);
 }
 assert.doesNotMatch(init,/dataStore\.get\(|runBlocking/);
});
test('R24 onCreate Qobuz observer collects a typed snapshot and retains source switching',()=>{
 const observer=section('var isFirstQobuzEmit = true','                    player.stop()');
 assert.match(observer,/dataStore\.data\s*\.map\s*\{/);
 assert.match(observer,/\.distinctUntilChanged\(\)/);
 assert.match(observer,/\.collect \{ values ->/);
 assert.match(observer,/squid = values\[4\]/);
 assert.match(observer,/jumo = values\[7\]/);
 assert.match(observer,/songUrlCache\.invalidate\(mediaId\)/);
 assert.match(observer,/qobuzMissUntilMs\.clear\(\)/);
 assert.doesNotMatch(observer,/applyQobuzEndpointOverrides\(\)/);
 assert.doesNotMatch(observer,/settingsSignature\.split|joinToString\("\|"\)/);
});
test('R24 override function has no blocking reads, no disabled Qobuz regression',()=>{
 const f=section('private fun applyQobuzEndpointOverrides(','private fun qobuzCacheKey(');
 assert.match(f,/QobuzAudioProvider\.configureEndpoints\(/);
 assert.doesNotMatch(f,/dataStore|runBlocking|withContext/);
 assert.match(service,/cachedEnableQobuz/);
});
test('R24 preserves R22 recovery, R21 player and R17/R19 Cover quantity/order',()=>{
 const policy=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');
 assert.match(policy,/MAX_BUFFER_STALL_RECOVERIES = 2/);
 assert.match(service,/onAudioPositionAdvancing\(/);
 assert.match(service,/BUFFER_STALL_RECOVERY/);
 assert.match(cover,/CoverSwipeBridge\.bind\(sessionKey\)/);
 const order=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt','utf8');
 assert.match(order,/it == "cover.info"/);
});
