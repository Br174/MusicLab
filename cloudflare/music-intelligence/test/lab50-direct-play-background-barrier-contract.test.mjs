import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

function functionSlice(startMarker, endMarker) {
  const start = browser.indexOf(startMarker);
  const end = browser.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `missing slice ${startMarker}`);
  return browser.slice(start, end);
}

test('LAB50 keeps the LAB41 Player buono core untouched', () => {
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  assert.match(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});

test('verified cover play has no YouTube metadata hydration before playQueue', () => {
  const playResolved = functionSlice(
    'suspend fun playResolvedContext(selectedFingerprint: String)',
    'suspend fun loadPage(',
  );
  assert.doesNotMatch(playResolved, /YouTube\.queue\(/);
  assert.doesNotMatch(playResolved, /withTimeoutOrNull\(/);
  assert.match(playResolved, /session\.preparedVideoSongs\[selectedId\]\?\.toMediaItem\(\)/);
  assert.match(playResolved, /MediaMetadata\(/);
  assert.match(playResolved, /id = selectedId/);
  assert.match(playResolved, /connection\.playQueue\(/);
});

test('play path does not hydrate or append a delayed next item', () => {
  const playResolved = functionSlice(
    'suspend fun playResolvedContext(selectedFingerprint: String)',
    'suspend fun loadPage(',
  );
  assert.doesNotMatch(playResolved, /addToQueue\(/);
  assert.doesNotMatch(playResolved, /nextSeed/);
  assert.doesNotMatch(playResolved, /nextSong/);
  assert.doesNotMatch(playResolved, /while \(connection\.isPlaybackPriorityBurstActive\(\)\)/);
});

test('playback cancellation owns a persistent background barrier', () => {
  const pause = functionSlice(
    'fun pauseCoverBackgroundForPlayback()',
    'fun resumeCoverBackgroundAfterPlaybackBurst()',
  );
  assert.match(pause, /backgroundPausedForPlayback = true/);
  for (const job of ['searchJob', 'paginationJob', 'verificationJob', 'videoPreloadJob', 'backgroundResumeJob']) {
    assert.match(pause, new RegExp(job + '\\\?\\\.cancel\\\(\\\)'));
  }

  const resume = functionSlice(
    'fun resumeCoverBackgroundAfterPlaybackBurst()',
    'fun saveDecision(',
  );
  assert.match(resume, /while \(playbackIsActive\(\)\)/);
  assert.match(resume, /backgroundPausedForPlayback = false/);
  assert.match(resume, /scheduleDiscogsVerification\(\)/);
  assert.match(resume, /scheduleVideoPreload\(\)/);
});

test('automatic preload verification and progressive pagination respect playback ownership', () => {
  assert.match(browser, /fun backgroundWorkBlocked\(\): Boolean = backgroundPausedForPlayback \|\| playbackIsActive\(\)/);

  const preload = functionSlice('fun scheduleVideoPreload()', 'fun scheduleDiscogsVerification()');
  assert.match(preload, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(preload, /if \(backgroundWorkBlocked\(\)\) break/);

  const verification = functionSlice('fun scheduleDiscogsVerification()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) break/);

  const pagination = functionSlice('fun loadNextPage()', 'fun retryMissingVideo(');
  assert.match(pagination, /if \(backgroundWorkBlocked\(\)\) return/);
});
