import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('Cover background work is bounded to the visible window', () => {
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 8/);
  assert.match(browser, /DIRECT_VIDEO_PARALLELISM = 3/);
  assert.match(browser, /DIRECT_BACKGROUND_PREFETCH_AHEAD = 4/);
  assert.match(browser, /DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 48/);
  assert.match(browser, /isPlaybackPriorityBurstActive\(\) == true/);
});

test('tapped Cover row owns resources before playback', () => {
  const start = browser.indexOf('fun play(seed: DiscogsVersionSeed)');
  assert.ok(start >= 0);
  const end = browser.indexOf('LaunchedEffect(', start);
  const play = browser.slice(start, end);
  assert.match(play, /pauseCoverBackgroundForPlayback\(\)/);
  assert.match(play, /resolveVideoChunk\(listOf\(currentSeed\)\)/);
  assert.match(play, /beginPlaybackPriorityBurst\("musiclab-version"\)/);
  assert.match(play, /resumeCoverBackgroundAfterPlaybackBurst\(\)/);
});

test('Cover pauses pagination verification and video preload while first sound is pending', () => {
  const start = browser.indexOf('fun pauseCoverBackgroundForPlayback()');
  const end = browser.indexOf('fun resumeCoverBackgroundAfterPlaybackBurst()', start);
  const pause = browser.slice(start, end);
  assert.match(pause, /paginationJob\?\.cancel\(\)/);
  assert.match(pause, /verificationJob\?\.cancel\(\)/);
  assert.match(pause, /videoPreloadJob\?\.cancel\(\)/);
});

test('queue tail waits for READY and hydrates only one following song', () => {
  const start = browser.indexOf('suspend fun playResolvedContext');
  const end = browser.indexOf('suspend fun loadPage', start);
  const resolved = browser.slice(start, end);
  assert.match(resolved, /while \(connection\.isPlaybackPriorityBurstActive\(\)\)/);
  assert.match(resolved, /connection\.playbackState\.value != Player\.STATE_READY/);
  assert.match(resolved, /val nextSeed/);
  assert.match(resolved, /firstOrNull\(\)/);
  assert.doesNotMatch(resolved, /missingIds\.chunked/);
});

test('player fallback prewarm is speculative and cancellable', () => {
  assert.match(service, /playerResolverPrewarmJob/);
  assert.match(service, /InnerTubeXPlayer\.prewarm\(\)/);
  assert.match(service, /playerResolverPrewarmJob\?\.cancel\(\)/);
  assert.match(service, /private const val SMART_PRELOAD_TRACKS = 1/);
});

test('approved LAB41 fast stream lane remains byte-contract compatible', () => {
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  assert.match(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});
