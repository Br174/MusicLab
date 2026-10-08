import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('LAB62 upstream Meld playback core is selectively restored', () => {
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS/);
  assert.doesNotMatch(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});

test('LAB48 removes speculative modern resolver prewarm from MusicService', () => {
  assert.doesNotMatch(service, /InnerTubeXPlayer\.prepare\(\)/);
  assert.doesNotMatch(service, /InnerTubeXPlayer\.prewarm\(\)/);
  assert.doesNotMatch(service, /playerResolverPrewarmJob/);
  assert.doesNotMatch(service, /modern stream fallback prewarm/);
});

test('tapped Cover row receives playback priority before any resolver work', () => {
  const start = browser.indexOf('fun play(seed: DiscogsVersionSeed)');
  const end = browser.indexOf('LaunchedEffect(', start);
  assert.ok(start >= 0 && end > start);
  const play = browser.slice(start, end);
  const priority = play.indexOf('beginPlaybackPriorityBurst("musiclab-version")');
  const resolve = play.indexOf('resolveVideoChunk(listOf(currentSeed))');
  assert.ok(priority >= 0, 'priority burst missing');
  assert.ok(resolve >= 0, 'tapped-row resolver missing');
  assert.ok(priority < resolve, 'resolver starts before playback priority');
  assert.equal(
    (play.match(/beginPlaybackPriorityBurst\("musiclab-version"\)/g) || []).length,
    1,
    'priority burst must have one owner in the tap path',
  );
});

test('tapped Cover row resolver is bounded and background work stays paused', () => {
  assert.match(browser, /DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS = 3_000L/);
  const start = browser.indexOf('fun play(seed: DiscogsVersionSeed)');
  const end = browser.indexOf('LaunchedEffect(', start);
  const play = browser.slice(start, end);
  assert.match(play, /withTimeoutOrNull\(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS\)/);
  assert.match(play, /pauseCoverBackgroundForPlayback\(\)/);
  assert.match(play, /resumeCoverBackgroundAfterPlaybackBurst\(\)/);
});
