import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const base = 'app/src/main/kotlin/com/metrolist/music/';
const service = readFileSync(base+'playback/MusicService.kt','utf8');
const policy = readFileSync(base+'playback/PlaybackSprintPolicy.kt','utf8');
const source = service.slice(service.indexOf('private fun createDataSourceFactory('),
                             service.indexOf('private fun createMediaSourceFactory('));
const queue = service.slice(service.indexOf('fun playQueue('),service.indexOf('fun playNextAutomix('));
const section = service.slice(service.indexOf('private fun playSectionEffective('),
                              service.indexOf('fun playNow(item: MediaItem)'));
const transition = service.slice(service.indexOf('override fun onMediaItemTransition('),
                                 service.indexOf('override fun onIsPlayingChanged('));

test('R56: valid signed stream URL hits before Room for ALL sections even after burst', () => {
  const beforeDb = source.slice(0,source.indexOf('val storedFormat = runBlocking(Dispatchers.IO)'));
  assert.match(beforeDb,/if \(!mediaId\.startsWith\("local:"\) &&\s*!bypassCacheForQualityChange\.contains\(mediaId\)/);
  assert.match(beforeDb,/songUrlCache\[mediaId\]\?\.let \{ cachedStream ->/);
  assert.match(beforeDb,/dataSpec\.withResolvedStream\(cachedStream\)/);
  assert.doesNotMatch(beforeDb,/if \(playbackPriorityBurstActive && !mediaId/);
});

test('R56: local/download cache precedence bypasses optional provider cascade', () => {
  const qobuz = source.indexOf('// Qobuz lossless attempt:');
  const fast = source.indexOf('if (!shouldBypassCache) {');
  assert.ok(fast >= 0 && fast < qobuz,'cache must be checked before Qobuz');
  assert.match(source.slice(fast,qobuz),/downloadCache\.isCached\(/);
  assert.match(source.slice(fast,qobuz),/playerCache\.isCached\(/);
  assert.match(source,/val qobuzEnabled =\s*!playbackPriorityBurstActive && cachedEnableQobuz/);
  assert.match(source,/songUrlCache\.invalidate|songUrlCache\[mediaId\]/);
});

test('R56: explicit queues and section selections share audio priority, not restored paused queues', () => {
  assert.match(policy,/fun shouldPrioritizeQueueStart\(playWhenReady: Boolean, restoringQueue: Boolean\)/);
  assert.match(policy,/playWhenReady && !restoringQueue/);
  assert.match(queue,/beginPlaybackPriorityBurst\("queue-preload-start"\)/);
  assert.match(queue,/beginPlaybackPriorityBurst\("queue-initial-start"\)/);
  assert.match(section,/beginPlaybackPriorityBurst\("section-direct-start"\)/);
  assert.match(service,/beginPlaybackPriorityBurst\("play-now"\)/);
});

test('R56: auto transition protects new audio but does not trigger duplicate taps or Cast', () => {
  assert.match(transition,/MEDIA_ITEM_TRANSITION_REASON_AUTO/);
  assert.match(transition,/MEDIA_ITEM_TRANSITION_REASON_REPEAT/);
  assert.match(transition,/player.playWhenReady && !playbackPriorityBurstActive/);
  assert.match(transition,/castConnectionHandler\?\.isCasting\?\.value != true/);
  assert.match(transition,/beginPlaybackPriorityBurst\("queue-auto-transition"\)/);
});

test('R56: no seek reset, no duplicate player, and protected buffer recovery remain unchanged', () => {
  assert.match(service,/BUFFER_STALL_RECOVERY/);
  assert.match(service,/PlaybackSprintPolicy.shouldRecoverBufferStall\(/);
  assert.match(service,/playSectionEffective\(MusicLabManualVersionStore.apply\(this, item\)/);
  assert.match(service,/player.seekTo\(0L\)/);
  assert.match(service,/SMART_PRELOAD_PREFIX_BYTES = 384L \* 1024L/);
  assert.match(service,/PLAYBACK_PRIORITY_BURST_MAX_MS = 15_000L/);
});
