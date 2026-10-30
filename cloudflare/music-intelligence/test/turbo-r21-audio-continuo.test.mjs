import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const service = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const browser = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const policy = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');
function between(src,begin,end) {
  const a=src.indexOf(begin), b=src.indexOf(end,a+begin.length);
  assert.ok(a>=0 && b>a,'missing region '+begin);
  return src.slice(a,b);
}
test('R21 corrects same-ID IDLE/ENDED replay but never re-prepares READY',()=>{
 const play=between(service,'fun playNow(item: MediaItem)','fun playNext(items: List<MediaItem>)');
 const same=between(play,'if (player.currentMediaItem?.mediaId == item.mediaId)','val currentIndex');
 assert.match(same,/shouldPrepareExistingTrack\(player.playbackState\)/);
 assert.match(same,/player.prepare\(\)/);
 assert.ok(same.indexOf('shouldPrepareExistingTrack') < same.indexOf('player.prepare()'));
 assert.match(policy,/playbackState == Player.STATE_IDLE \|\| playbackState == Player.STATE_ENDED/);
});
test('R21 resolver normalization never waits synchronously for main looper',()=>{
 const normalizer=between(service,'private fun applyAudioNormalizationBeforePlayback(','private fun setupAudioNormalization()');
 assert.doesNotMatch(normalizer,/runBlocking/);
 assert.match(normalizer,/scope.launch\(Dispatchers.Main.immediate\)/);
 assert.match(normalizer,/currentMediaItem\?\.mediaId != mediaId/);
});
test('R21 first audio and underrun diagnostics are independent of READY flag',()=>{
 assert.match(service,/onAudioPositionAdvancing\(/);
 assert.match(service,/"AUDIO_POSITION_ADVANCING"/);
 assert.match(service,/onAudioUnderrun\(/);
 assert.match(service,/"AUDIO_UNDERRUN"/);
 assert.match(service,/lab31FirstAudioReportedTapMs != tapMs/);
 assert.match(service,/if \(lab31AudioEvents.size >= 48\)/);
});
test('R21 prioritizes only one verified next Cover in existing prewarm worker',()=>{
 const lane=between(service,'private fun scheduleSmartPlaybackPreload()',
     'private suspend fun playbackHasHealthyPreloadReserve');
 assert.match(lane,/PlaybackSprintPolicy.nextPreloadMediaId\(/);
 assert.match(lane,/for \(mediaItem in listOfNotNull\(chosenItem\)\)/);
 assert.match(lane,/withContext\(Dispatchers.IO\)/);
 assert.match(lane,/45_000L else SMART_PRELOAD_WAIT_MS/);
 assert.match(service,/smartPreloadJob\?\.cancel\(\)/);
 assert.match(policy,/Regex\("\[A-Za-z0-9_-\]\{11\}"\)/);
 const b=between(browser,'suspend fun playResolvedContext(','suspend fun loadPage(');
 assert.match(b,/connection.service.setCoverNextPreloadCandidate\(nextVerifiedId\)/);
 assert.match(b,/filter\(::hasPublishableVideo\)/);
});
test('R21 preserves R20/R19 buffers, exclusion and Cover contracts',()=>{
 assert.match(service,/PLAYBACK_START_BUFFER_MS = 900/);
 assert.match(service,/PLAYBACK_REBUFFER_MS = 6_000/);
 assert.match(policy,/COVER_PRELOAD_RESERVE_MS = 40_000L/);
 const cover=readFileSync('behaviors/musiclab-cover.json','utf8');
 assert.match(cover,/"approvedR17QualityProgressive"/);
 assert.match(browser,/CoverSwipeBridge.bind\(sessionKey\)/);
 assert.match(browser,/connection.playNow\(selectedItem\)/);
});
