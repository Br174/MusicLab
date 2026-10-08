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

test('LAB62 retains PlayerConnection behavior with upstream Meld extractor', () => {
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS/);
  assert.doesNotMatch(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});

test('LAB59 verified video goes direct to native Player without duplicate preflight', () => {
  const playResolved = functionSlice(
    'suspend fun playResolvedContext(selectedFingerprint: String)',
    'suspend fun loadPage(',
  );
  assert.match(playResolved, /val selectedSong = session\.preparedVideoSongs\[selectedId\]/);
  assert.doesNotMatch(playResolved, /withTimeoutOrNull\(900L\)/);
  assert.doesNotMatch(playResolved, /needsDirectStreamProbe/);
  assert.doesNotMatch(playResolved, /YouTube\.queue\(videoIds = listOf\(selectedId\)\)/);
  assert.doesNotMatch(playResolved, /connection\.service\.getStreamUrl\(selectedId\)/);
  assert.match(playResolved, /selectedSong\?\.toMediaMetadata\(\)\?\.copy\(thumbnailUrl = selectedArtwork/);
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

test('LAB59 automatic preload stops during playback, protecting audio from source probing', () => {
  assert.match(browser, /fun backgroundWorkBlocked\(\): Boolean = backgroundPausedForPlayback \|\| playbackIsCritical\(\)/);

  const preload = functionSlice('fun scheduleVideoPreload()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(preload, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(preload, /if \(backgroundWorkBlocked\(\) \|\| playbackIsNormallyPlaying\(\)\) break/);
  assert.match(preload, /DIRECT_COVER_PLAYBACK_BATCH_SIZE/);

  const verification = functionSlice('fun scheduleDiscogsVerification()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) return/);
  assert.match(verification, /if \(backgroundWorkBlocked\(\)\) break/);

  const pagination = functionSlice('fun loadNextPage()', 'fun retryMissingVideo(');
  // LAB60: user paging must work even while audio is buffering; preload remains guarded.
  assert.doesNotMatch(pagination, /if \(backgroundWorkBlocked\(\) \|\| currentPage <= 0/);
});
