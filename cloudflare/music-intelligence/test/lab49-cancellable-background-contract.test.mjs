import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const discogs = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const sources = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const archive = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabArchiveScreen.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('LAB62 retains Qos guards but uses upstream Meld extractor', () => {
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS/);
  assert.doesNotMatch(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});

test('Discogs retry and rate-limit path is fully coroutine-cancellable', () => {
  assert.doesNotMatch(discogs, /Thread\.sleep\(/);
  assert.doesNotMatch(discogs, /\.execute\(\)/);
  assert.match(discogs, /suspendCancellableCoroutine/);
  assert.match(discogs, /invokeOnCancellation \{ call\.cancel\(\) \}/);
  assert.match(discogs, /delay\(retryMs\)/);
  assert.match(discogs, /if \(waitMs > 0L\) delay\(waitMs\)/);
});

test('Cloud and COVER.INFO network lanes cancel their real OkHttp calls', () => {
  for (const source of [cloud, sources]) {
    assert.doesNotMatch(source, /Thread\.sleep\(/);
    assert.doesNotMatch(source, /\.execute\(\)/);
    assert.match(source, /suspendCancellableCoroutine/);
    assert.match(source, /call\.cancel\(\)/);
  }
  assert.match(sources, /delay\(180\)/);
});

test('Cover background work stays blocked through playback critical start, then may resume during normal playback', () => {
  const criticalStart = browser.indexOf('fun playbackIsCritical()');
  const criticalEnd = browser.indexOf('fun backgroundWorkBlocked()', criticalStart);
  assert.ok(criticalStart >= 0 && criticalEnd > criticalStart);
  const critical = browser.slice(criticalStart, criticalEnd);
  assert.match(critical, /isPlaybackPriorityBurstActive\(\) == true/);
  assert.match(critical, /playbackState\?\.value == Player\.STATE_BUFFERING/);
  assert.doesNotMatch(critical, /isEffectivelyPlaying/);

  const start = browser.indexOf('fun resumeCoverBackgroundAfterPlaybackBurst()');
  const end = browser.indexOf('fun saveDecision(', start);
  assert.ok(start >= 0 && end > start);
  const resume = browser.slice(start, end);
  assert.match(resume, /while \(playbackIsCritical\(\)\)/);
  assert.match(resume, /backgroundPausedForPlayback = false/);
  const guard = resume.lastIndexOf('playbackIsCritical()');
  const restart = resume.indexOf('scheduleDiscogsVerification()');
  assert.ok(guard >= 0 && restart > guard, 'provider work restarts before critical-playback guard');
});

test('one tapped row owns one playback coroutine and screen exit cancels all screen jobs', () => {
  assert.match(browser, /playbackLaunchJob\?\.cancel\(\)/);
  assert.match(browser, /playbackLaunchJob = scope\.launch/);
  assert.match(browser, /DisposableEffect\(sessionKey\)/);
  for (const job of [
    'playbackLaunchJob',
    'searchJob',
    'paginationJob',
    'verificationJob',
    'videoPreloadJob',
    'backgroundResumeJob',
  ]) {
    assert.match(browser, new RegExp(job + '\\\?\\\.cancel\\\(\\\)'));
  }
});

test('leaving Archivio MusicLab hard-stops archive HTTP calls', () => {
  assert.match(cloud, /internal fun cancelArchiveRequests\(\)/);
  assert.match(cloud, /activeArchiveCalls/);
  assert.match(archive, /DisposableEffect\(Unit\)/);
  assert.match(archive, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
});
