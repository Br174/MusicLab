import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
const connection = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt', 'utf8');
const environment = fs.readFileSync('uab-project.env', 'utf8');
const player = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

function between(source, start, end) {
  const i = source.indexOf(start);
  assert.ok(i >= 0, `Missing section: ${start}`);
  const j = source.indexOf(end, i + start.length);
  assert.ok(j > i, `Missing end: ${end}`);
  return source.slice(i, j);
}

test('LAB05 releases the native audio governor when a 50-video Turbo burst is sealed', () => {
  const burst = between(browser, 'if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02', '\n        } else {\n        searchJob =');
  assert.match(burst, /session\.turboFinished = true/);
  assert.match(burst, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(burst, /active = false, heavy = false/);
  assert.match(burst, /publishedCoverSnapshots = finalRanked/);
  assert.match(burst, /TURBO_BURST_DEADLINE_MS/);
  assert.match(browser, /if \(isTurbo && session\.turboFinished\) return emptyList\(\)/);
});

test('LAB05 does not reactivate the governor on a completed Cover re-entry or after a tap', () => {
  const dispose = between(browser, 'DisposableEffect(sessionKey) {', '\n    fun persistInputs()');
  assert.match(dispose, /active = !isTurbo \|\| !session\.turboFinished/);
  assert.match(dispose, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(dispose, /setCoverPerformanceLoad\(active = false, heavy = false\)/);
  const resume = between(browser, 'fun resumeCoverBackgroundAfterPlaybackBurst()', '\n    fun saveDecision(');
  assert.match(resume, /if \(isTurbo && mode == DiscogsDirectMode\.COVER && session\.turboFinished\)/);
  assert.match(resume, /setCoverPerformanceLoad\(active = false, heavy = false\)/);
  assert.match(resume, /return\s*\}/);
});

test('LAB05 taps play the immutable certified ID immediately, never through asynchronous filtered queue', () => {
  const selected = between(browser, 'suspend fun playResolvedContext(', '\n    suspend fun loadPage(');
  const direct = selected.indexOf('connection.playNow(selectedItem)');
  const queued = selected.indexOf('connection.playQueue(');
  assert.ok(direct > 0 && queued > direct);
  assert.match(selected, /publishedCoverSnapshots\.firstOrNull/);
  assert.match(selected, /connection\.service\.isPlayerReady\.value/);
  assert.match(selected, /if \(isTurbo && mode == DiscogsDirectMode\.COVER/);
  assert.match(connection, /fun playNow\(item: MediaItem\)/);
  assert.match(service, /fun playNow\(item: MediaItem\)/);
  assert.match(service, /player\.seekTo\(targetIndex, 0L\)/);
  const tap = between(browser, 'fun play(seed: DiscogsVersionSeed)', '\n    // LAB64:');
  assert.match(tap, /publishedCoverSnapshots\.firstOrNull/);
  assert.match(tap, /playResolvedContext\(currentSeed\.fingerprint\)/);
});

test('LAB05 preserves original decoder, same Turbo family and in-place signature', () => {
  const blob = createHash('sha1').update(Buffer.concat([
    Buffer.from('blob ' + player.length + '\0'), player,
  ])).digest('hex');
  assert.equal(blob, '03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(environment, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(environment, /UAB_UPDATE_FAMILY_VERSION_CODE="1005"/);
  assert.match(environment, /UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 05"/);
  assert.match(environment, /UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:/);
});
