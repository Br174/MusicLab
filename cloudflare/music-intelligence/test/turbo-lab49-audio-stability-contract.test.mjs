import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const service = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
const screen = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
function section(source, before, after) {
  const a = source.indexOf(before);
  assert.ok(a >= 0, 'missing section: ' + before);
  const b = source.indexOf(after, a + before.length);
  assert.ok(b > a, 'missing end: ' + after);
  return source.slice(a, b);
}

test('LAB49 cancels heavy Cover work on screen exit and prioritizes playback', () => {
  const lifecycle = section(screen, 'DisposableEffect(sessionKey)', 'fun persistInputs()');
  assert.match(lifecycle, /coverRecoveryJob\?\.cancel\(\)/);
  assert.match(lifecycle, /setCoverPerformanceLoad\(active = false, heavy = false\)/);
  const priority = section(screen, 'fun pauseCoverBackgroundForPlayback()', 'fun resumeCoverBackgroundAfterPlaybackBurst()');
  assert.match(priority, /coverRecoveryJob\?\.cancel\(\)/);
  assert.match(priority, /coverRecoveryJob = null/);
  assert.match(priority, /turboAutoVideoJob\?\.cancel\(\)/);
});

test('LAB49 never runs extended documentary recovery against playing or buffering music', () => {
  assert.match(screen, /while \(backgroundWorkBlocked\(\) \|\|/);
  assert.match(screen, /maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\)\) 1 else 2/);
  assert.match(screen, /playerConnection\?\.service\?\.isCoverAudioAtRisk\(\) == true/);
  assert.match(screen, /deepRecovery = true/);
  assert.match(screen, /CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
});

test('LAB49 Media3 timeline and state reads occur on main player looper, not IO', () => {
  const prewarm = section(service, 'private fun scheduleSmartPlaybackPreload()', 'private suspend fun playbackHasHealthyPreloadReserve');
  assert.match(prewarm, /scope\.launch\(Dispatchers\.Main\.immediate \+ SilentHandler\)/);
  assert.match(prewarm, /withContext\(Dispatchers\.IO\) \{\s*prewarmMediaItemPrefix\(mediaItem, anchorId\)/);
  assert.match(service, /private suspend fun playbackHasHealthyPreloadReserve\(anchorId: String\): Boolean =\s*withContext\(Dispatchers\.Main\.immediate\)/);
  const background = section(service, 'private suspend fun prewarmMediaItemPrefix(', '/**\n     * Pre-caches the next N tracks');
  assert.doesNotMatch(background, /player\.currentMediaItem\?/);
  assert.doesNotMatch(background, /player\.playbackState/);
  assert.doesNotMatch(background, /player\.totalBufferedDuration/);
});

test('LAB49 next-track speculative download waits for healthy reserve and yields to Cover', () => {
  assert.match(service, /SMART_PRELOAD_STABLE_BUFFER_MS = 30_000L/);
  assert.match(service, /SMART_PRELOAD_RESUME_BUFFER_MS = 26_000L/);
  // R20 supersedes the blanket cover-active block with a 40s buffered
  // threshold and a strict no-work-while-cover-heavy guard.
  assert.match(service, /if \(playbackPriorityBurstActive\) return/);
  assert.match(service, /PlaybackSprintPolicy\.requiredPreloadReserve/);
  assert.match(service, /coverHeavyLoad/);
  assert.match(service, /if \(!playbackHasHealthyPreloadReserve\(anchorId\)\) return/);
  assert.match(service, /writer\.cancel\(\)/);
});

test('LAB49 expired and broken stream retries cannot override a new selection', () => {
  for(const [start, finish] of [
    ['private fun handleRangeNotSatisfiableError(', 'private fun handlePageReloadError('],
    ['private fun handlePageReloadError(', 'private fun handleExpiredUrlError('],
    ['private fun handleFileNotFoundError(', 'private fun handleStreamClientError('],
    ['private fun handleGenericIOError(', 'private fun handleFinalFailure('],
  ]) {
    const retry = section(service, start, finish);
    assert.match(retry, /player\.currentMediaItem\?\.mediaId != mediaId/);
    assert.match(retry, /player\.currentMediaItemIndex != failedIndex/);
    assert.match(retry, /!player\.playWhenReady|player\.playWhenReady != shouldContinue/);
    assert.match(retry, /player\.prepare\(\)/);
  }
  assert.match(service, /private fun refreshStreamAndRetry\(/);
  assert.match(service, /val changedSelection =/);
});

test('LAB49 adapts rebuffer reserve without regressing rapid initial song start', () => {
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 900/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 6_000/);
  assert.match(service, /setBufferDurationsMs\(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS\)/);
  assert.match(service, /lab31AudioEvent\("BUFFERING"/);
  assert.match(service, /lab31AudioEvent\("PLAYER_ERROR"/);
});

test('LAB49 retains the Cover first-batch gate and work-based rejection', () => {
  assert.match(screen, /coverRound = if \(appendVerifiedRound\) coverRoundNumber else 1/);
  assert.match(screen, /CoverSameWorkGate\.hasDocumentaryRelation/);
  assert.match(screen, /val originallySparse = publishedCoverSnapshots\.size < 15/);
});
