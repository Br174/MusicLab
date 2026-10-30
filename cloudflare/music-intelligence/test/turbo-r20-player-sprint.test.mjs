import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const service = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const browser = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const policy = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');

function section(text, begin, end) {
  const start=text.indexOf(begin);
  const finish=text.indexOf(end,start+begin.length);
  assert.ok(start>=0 && finish>start, 'missing '+begin);
  return text.slice(start,finish);
}

test('R20 rapid cache-hit lane happens BEFORE Room database format read',()=>{
 const lane=section(service,'private fun createDataSourceFactory(','// Qobuz lossless attempt:');
 assert.ok(lane.indexOf('songUrlCache[mediaId]?.let { cachedStream ->')>=0);
 assert.ok(lane.indexOf('songUrlCache[mediaId]?.let { cachedStream ->') <
      lane.indexOf('val storedFormat = runBlocking(Dispatchers.IO)'));
 assert.match(lane,/!bypassCacheForQualityChange\.contains\(mediaId\)/);
 assert.match(lane,/!mediaId\.startsWith\("local:"\)/);
 assert.match(lane,/dataSpec\.withResolvedStream\(cachedStream\)/);
});

test('R20 prewarm scheduler waits for healthy buffer rather than rejecting READY too early',()=>{
 const lane=section(service,'private fun scheduleSmartPlaybackPreload()',
     'private suspend fun playbackHasHealthyPreloadReserve');
 assert.doesNotMatch(lane,/if \(playbackPriorityBurstActive \|\| coverSectionActive\) return/);
 assert.match(lane,/scope\.launch\(Dispatchers\.Main\.immediate \+ SilentHandler\)/);
 assert.match(lane,/PlaybackSprintPolicy\.canPreload/);
 assert.match(lane,/requiredPreloadReserve/);
 assert.match(lane,/coverHeavyLoad/);
 assert.match(lane,/withContext\(Dispatchers\.IO\) \{\s*prewarmMediaItemPrefix/);
 assert.match(policy,/COVER_PRELOAD_RESERVE_MS = 40_000L/);
 assert.match(service,/SMART_PRELOAD_PREFIX_BYTES = 384L \* 1024L/);
 assert.match(service,/SMART_PRELOAD_WAIT_MS = 18_000L/);
});

test('R20 protects low-ready-buffer Cover before resuming documentary discovery',()=>{
 const critical=section(browser,'fun playbackIsCritical(): Boolean','fun backgroundWorkBlocked()');
 assert.match(critical,/isCoverAudioAtRisk\(\) == true/);
 const resume=section(browser,'fun resumeCoverBackgroundAfterPlaybackBurst()','fun saveDecision(');
 assert.match(resume,/while \(isActive && playbackIsCritical\(\)\) delay\(200L\)/);
 assert.match(resume,/if \(isActive\) continueAfterAudioRecovery\(\)/);
 const governor=section(service,'fun isCoverAudioAtRisk(): Boolean','private fun effectiveAudioQuality');
 assert.match(governor,/COVER_AUDIO_SAFETY_BUFFER_MS/);
 assert.match(governor,/Player.STATE_BUFFERING/);
});

test('R20 does not alter buffer targets, swipe identity, Cover list or R19 source-order',()=>{
 assert.match(service,/PLAYBACK_START_BUFFER_MS = 900/);
 assert.match(service,/PLAYBACK_REBUFFER_MS = 6_000/);
 assert.match(service,/PLAYBACK_PRIORITY_BURST_MAX_MS = 15_000L/);
 assert.match(browser,/CoverSwipeBridge\.bind\(sessionKey\)/);
 assert.match(browser,/connection\.playNow\(selectedItem\)/);
 assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ /);
 const order=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt','utf8');
 assert.match(order,/private fun musicalSourceTier/);
 assert.match(order,/it == "cover.info"/);
 const admission=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt','utf8');
 assert.match(admission,/fun minimumRound\([\s\S]*?\): Int = 1/);
});
