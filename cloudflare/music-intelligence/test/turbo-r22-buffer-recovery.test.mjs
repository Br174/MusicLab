import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const service=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const policy=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');
const browser=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
function segment(begin,end) {
 const a=service.indexOf(begin), b=service.indexOf(end,a+begin.length);
 assert.ok(a>=0 && b>a,'missing segment '+begin);
 return service.slice(a,b);
}
test('R22 protects stalled playback at any seek/position and never retries stale selection',()=>{
 const lane=segment('private fun updateInitialBufferRecovery(','override fun onEvents(');
 assert.match(lane,/PlaybackSprintPolicy\.shouldRecoverBufferStall/);
 assert.match(lane,/player\.currentMediaItem\?\.mediaId == mediaId/);
 assert.match(lane,/player\.currentMediaItemIndex == stalledIndex/);
 assert.match(lane,/player\.playWhenReady/);
 assert.match(lane,/isNetworkConnected\.value/);
 assert.doesNotMatch(lane,/INITIAL_BUFFER_RECOVERY_POSITION_MS|currentPosition < 5_000/);
});
test('R22 refreshes a stuck URL without evicting good cache or producing unbounded retry loops',()=>{
 const lane=segment('private fun updateInitialBufferRecovery(','override fun onEvents(');
 assert.match(lane,/BUFFER_STALL_RECOVERY/);
 assert.match(lane,/refreshStreamAndRetry\(/);
 assert.doesNotMatch(lane,/performAggressiveCacheClear/);
 assert.match(lane,/bufferStallRecoveryAttempts\+\+/);
 assert.match(policy,/MAX_BUFFER_STALL_RECOVERIES = 2/);
 assert.match(policy,/BUFFER_STALL_RECOVERY_DELAY_MS = 9_000L/);
 assert.match(service,/bufferStallRecoveryAttempts = 0/);
});
test('R22 protects audio reserve without modifying search, buffer startup or R21 diagnostic',()=>{
 assert.match(service,/COVER_AUDIO_SAFETY_BUFFER_MS = 18_000L/);
 assert.match(service,/PLAYBACK_START_BUFFER_MS = 900/);
 assert.match(service,/PLAYBACK_REBUFFER_MS = 6_000/);
 assert.match(service,/onAudioPositionAdvancing\(/);
 assert.match(service,/onAudioUnderrun\(/);
 assert.match(browser,/CoverSwipeBridge\.bind\(sessionKey\)/);
 const behavior=readFileSync('behaviors/musiclab-cover.json','utf8');
 assert.match(behavior,/"approvedR17QualityProgressive"/);
});
