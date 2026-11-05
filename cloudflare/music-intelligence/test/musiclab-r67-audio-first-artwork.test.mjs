import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = 'app/src/main/kotlin/com/metrolist/music/';
const read = name => fs.readFileSync(root + name, 'utf8');
const service = read('playback/MusicService.kt');
const gate = read('playback/MusicLabArtworkAudioGate.kt');
const fast = read('ui/component/MusicLabFastPath.kt');
const comp = read('ui/component/CompilationScreen.kt');
const chart = read('ui/screens/search/MusicLabChartArtwork.kt');
const hit = read('ui/screens/search/MusicLabHitParadeDiscArtwork.kt');
const extras = read('ui/screens/search/MusicLabLightArtwork.kt');
const dates = read('ui/component/CoverVisibleYearResolver.kt');
const hitRow = read('ui/screens/search/MusicLabHistoricalHitParade.kt');
const tests = fs.readFileSync('app/src/test/kotlin/com/metrolist/music/playback/MusicLabArtworkAudioGateTest.kt','utf8');

test('Media3 requests preempt optional artwork before stream resolution', () => {
  assert.match(service, /fun beginPlaybackPriorityBurst\(reason: String = "user-action"\) \{\s*\/\/ R67:[^\n]*[\s\S]*?MusicLabArtworkAudioGate\.onAudioRequested\(\)/);
  assert.match(service, /MusicLabArtworkAudioGate\.onPlayerState\(playbackState, player\.playWhenReady\)/);
  assert.match(service, /MusicLabArtworkAudioGate\.onPlayIntent\(playWhenReady\)/);
  assert.match(service, /fun playSectionNow\(item: MediaItem, sectionTitle: String\)/);
  assert.match(service, /beginPlaybackPriorityBurst\("play-now"\)/);
  assert.match(hitRow, /beginPlaybackPriorityBurst\("hit-parade-tap"\)/);
  assert.match(comp, /beginPlaybackPriorityBurst\("compilation-track-tap"\)/);
});

test('Artwork network slots are separate, cancellable, bounded, and never used by Media3', () => {
  assert.match(gate, /Semaphore\(2\)/);
  assert.match(gate, /FIRST_AUDIO_RESERVE_MS = 6_000L/);
  assert.match(gate, /ARTWORK_SAFE_WAIT_MS = 8_500L/);
  assert.match(gate, /while \(!mayStartOptionalArtwork\(\)\)/);
  assert.match(gate, /pending\.forEach \{ it\.cancel\(/);
  assert.match(gate, /onPlayerState\(state: Int, playWhenReady: Boolean\)/);
  assert.match(gate, /suspend fun <T> optionalLookup/);
  assert.doesNotMatch(gate, /playNow\(|prepare\(|seekTo\(|setMediaItem\(|YouTube\.queue\(/);
  assert.match(tests, /rebufferingPreemptsArtworkEvenAfterDeadline/);
  assert.match(tests, /metadataMayResumeAfterAudioHeadStart/);
});

test('No image source competes with a first-audio user search', () => {
  assert.match(fast, /suspend fun findArtwork\(/);
  assert.match(fast, /peek\(title, artist, compatible\) \?: MusicLabArtworkAudioGate\.optionalLookup/);
  assert.match(fast, /val song = MusicLabFastPath\.findArtwork\(/);
  assert.match(fast, /suspend fun find\(\s*title: String,/);
  assert.match(chart, /MusicLabArtworkAudioGate\.optionalLookup \{\s*gate\.withPermit \{ catalogueArtwork\(track\) \}/);
  assert.match(chart, /MusicLabArtworkAudioGate\.optionalLookup \{\s*resolveSong\(context, track\)/);
  assert.match(hit, /MusicLabArtworkAudioGate\.optionalLookup \{/);
  assert.match(hit, /MusicLabFastPath\.findArtwork\(title, artist\)/);
  assert.match(extras, /MusicLabArtworkAudioGate\.optionalLookup \{/);
  assert.match(dates, /MusicLabArtworkAudioGate\.optionalLookup \{/);
  assert.match(comp, /val song = MusicLabFastPath\.findArtwork\(/);
});

test('Tap path never waits on artwork gate or missing-disc thumbnail', () => {
  const start = hitRow.indexOf('val myTap = ++tapSerial');
  const end = hitRow.indexOf('}).padding(vertical = 6.dp)', start);
  assert.ok(start > 0 && end > start);
  const tap = hitRow.slice(start, end);
  assert.match(tap, /val ready = MusicLabHitParadeDiscArtwork\.rememberedPlayableMediaItem/);
  assert.match(tap, /MusicLabFastPath\.peek\(/);
  assert.match(tap, /MusicLabHitParadeVideoResolver\.find/);
  assert.doesNotMatch(tap, /MusicLabArtworkAudioGate\.optionalLookup/);
  assert.doesNotMatch(tap, /MusicLabLightArtwork\.recover/);
  assert.match(extras, /else if \(!manual && MusicLabArtworkAudioGate\.mayStartOptionalArtwork\(\)\)/);
});

test('Native MusicLab R64 artwork, R65 dates, R66 encoding stay available', () => {
  assert.match(hit, /MusicLabLightArtwork\.saved/);
  assert.match(chart, /sourceArtworkTrusted/);
  assert.match(comp, /MusicLabSectionPlayback\.select/);
  assert.match(read('ui/component/DiscogsDirectVersionBrowser.kt'), /CoverVisibleSortPolicy\.sort/);
  assert.match(read('ui/screens/search/MusicLabHistoricalExtraCharts.kt'), /MusicLabHistoricHtmlDecoder\.decode/);
});
