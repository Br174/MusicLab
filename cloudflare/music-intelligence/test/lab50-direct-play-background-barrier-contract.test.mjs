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

test('verified session video keeps fast lane while stale inherited ids are revalidated before playQueue', () => {
  const playResolved = functionSlice(
    'suspend fun playResolvedContext(selectedFingerprint: String)',
    'suspend fun loadPage(',
  );
  assert.match(playResolved, /var selectedSong = session\.preparedVideoSongs\[selectedId\]/);
  assert.match(playResolved, /val needsDirectStreamProbe =/);
  assert.match(playResolved, /selectedSong == null \|\|/);
  assert.match(playResolved, /YouTube\.queue\(videoIds = listOf\(selectedId\)\)/);
  assert.match(playResolved, /connection\.service\.getStreamUrl\(selectedId\)/);
  assert.match(playResolved, /selectedSong\?\.toMediaItem\(\)/);
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

test('playback cancellation owns a short critical barrier without killing Cover discovery', () => {
  const pause = functionSlice(
    'fun pauseCoverBackgroundForPlayback()',
    'fun resumeCoverBackgroundAfterPlaybackBurst()',
  );
  assert.match(pause, /backgroundPausedForPlayback = true/);
  for (const job of ['verificationJob', 'videoPreloadJob', 'backgroundResumeJob']) {
    assert.match(pause, new RegExp(job + '\\\?\\\.cancel\\\(\\\)'));
  }
  assert.doesNotMatch(pause, /searchJob\?\.cancel\(\)/);
  assert.doesNotMatch(pause, /paginationJob\?\.cancel\(\)/);

  const resume = functionSlice(
    'fun resumeCoverBackgroundAfterPlaybackBurst()',
    'fun saveDecision(',
  );
  assert.match(resume, /while \(playbackIsCritical\(\)\)/);
  assert.match(resume, /backgroundPausedForPlayback = false/);
  assert.match(resume, /scheduleDiscogsVerification\(\)/);
  assert.match(resume, /scheduleVideoPreload\(\)/);
});

test('automatic preload and verification respect only the critical playback barrier', () => {
  assert.match(browser, /fun backgroundWorkBlocked\(\): Boolean = backgroundPausedForPlayback \|\| playbackIsCritical\(\)/);

  const preload = functionSlice('fun scheduleVideoPreload()', 'fun scheduleDiscogsVerification()');
  assert.match(preload, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(preload, /if \(backgroundWorkBlocked\(\)\) break/);
  assert.match(preload, /DIRECT_COVER_PLAYBACK_BATCH_SIZE/);

  const verification = functionSlice('fun scheduleDiscogsVerification()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) break/);

  const pagination = functionSlice('fun loadNextPage()', 'fun retryMissingVideo(');
  assert.match(pagination, /if \(backgroundWorkBlocked\(\) \|\| currentPage <= 0 \|\| currentPage >= totalPages\) return/);
});
