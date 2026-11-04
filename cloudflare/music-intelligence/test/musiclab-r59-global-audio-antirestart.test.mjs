import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = 'app/src/main/kotlin/com/metrolist/music/';
const read = p=>fs.readFileSync(root+p,'utf8');
const service=read('playback/MusicService.kt');
const policy=read('playback/PlaybackSprintPolicy.kt');
const unit=fs.readFileSync('app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt','utf8');
const restoration=service.slice(service.indexOf('if (startupPrefs!![PersistentQueueKey]'),service.indexOf('// Single background consumer'));
const shuffle=service.slice(service.indexOf('override fun onShuffleModeEnabledChanged'),service.indexOf('override fun onRepeatModeChanged'));
const resolver=service.slice(service.indexOf('private fun createDataSourceFactory('),service.indexOf('private fun createMediaSourceFactory('));

test('R59: 1000ms stale state restoration no longer seeks or rewinds newly selected audio',()=>{
  assert.doesNotMatch(restoration,/delay\(1000\)/);
  assert.doesNotMatch(restoration,/player\.seekTo\(playerState\.currentMediaItemIndex/);
  assert.match(restoration,/playerVolume\.value = playerState\.volume\.coerceIn\(0f, 1f\)/);
  assert.match(restoration,/PlaybackSprintPolicy\.mayRestorePersistedQueue\(/);
  assert.match(restoration,/player\.mediaItemCount > 0/);
  assert.match(restoration,/lab31AudioEvent\("R59_STALE_QUEUE_RESTORE_IGNORED"\)/);
  assert.match(restoration,/lab31AudioEvent\("R59_RESTORE_NO_DELAYED_SEEK"\)/);
});

test('R59: shuffle expands existing queue but can NEVER reset current media or re-prepare',()=>{
  assert.match(shuffle,/PlaybackSprintPolicy\.mayApplyAsyncShuffle\(/);
  assert.match(shuffle,/queueRequestSerial/);
  assert.match(shuffle,/currentQueue === queue/);
  assert.match(shuffle,/player\.currentMediaItem\?\.mediaId == selectedMediaId/);
  assert.match(shuffle,/player\.mediaItems\.forEach/);
  assert.match(shuffle,/player\.addMediaItems\(missing\)/);
  assert.match(shuffle,/applyShuffleOrder\(/);
  assert.doesNotMatch(shuffle,/player\.setMediaItems\(/);
  assert.doesNotMatch(shuffle,/player\.prepare\(/);
});

test('R59: explicit user intent invalidates async shuffle and deferred queue load',()=>{
  const startQueue=service.slice(service.indexOf('fun playQueue('),service.indexOf('fun playNextAutomix('));
  const startDirect=service.slice(service.indexOf('private fun playSectionEffective('),service.indexOf('fun playNext('));
  assert.match(startQueue,/shuffleExpansionJob\?\.cancel\(\)/);
  assert.match(startDirect,/shuffleExpansionJob\?\.cancel\(\)/);
  assert.match(startDirect,/pendingQueuePreparation\?\.cancel\(\)/);
  assert.match(policy,/fun mayRestorePersistedQueue\(/);
  assert.match(policy,/fun mayApplyAsyncShuffle\(/);
  assert.match(unit,/persistedRestoreCannotRewindNewSelectionOrActiveSong/);
  assert.match(unit,/asyncShuffleMustNotResetNewerQueueOrCurrentTrack/);
});

test('R59: audio pinned before prepare, avoiding another restart on media transition',()=>{
  assert.match(service,/player\.setMediaItem\(MusicLabManualVersionStore\.apply\(/);
  assert.match(service,/preparedItems\[start\] = MusicLabManualVersionStore\.apply\(/);
  assert.match(service,/player\.setMediaItems\(preparedItems, start, initialStatus\.position\)/);
  assert.match(service,/MusicLabManualVersionStore\.apply\(this, item\)/);
});

test('R59: full offline audio cache is served BEFORE DB or network; partial prefix not mistaken as complete',()=>{
  const fast=resolver.indexOf('R59: an entirely cached audio range');
  const db=resolver.indexOf('val storedFormat = runBlocking(Dispatchers.IO)');
  assert.ok(fast >= 0 && db > fast);
  const block=resolver.slice(fast,db);
  assert.match(block,/ContentMetadata\.getContentLength/);
  assert.match(block,/required <= remaining/);
  assert.match(block,/audioCache\.isCached\(mediaId, dataSpec\.position, required\)/);
  assert.match(block,/return@Factory dataSpec/);
  assert.match(block,/R59_LOCAL_AUDIO_FASTPATH/);
  assert.match(resolver,/val qobuzEnabled =\s*!playbackPriorityBurstActive && cachedEnableQobuz/);
  assert.match(service,/PLAYBACK_START_BUFFER_MS = 1_600/);
  assert.match(service,/PLAYBACK_REBUFFER_MS = 8_000/);
  assert.match(service,/BUFFER_STALL_RECOVERY/);
});

test('R59: R58 global timing diagnostics remain readable and no second player introduced',()=>{
  assert.match(service,/R58_IS_PLAYING/);
  assert.match(service,/STREAM_RESOLVE/);
  assert.match(service,/lab31AudioEvent\("BUFFERING"/);
  assert.match(service,/val player =\s*ExoPlayer/);
});
