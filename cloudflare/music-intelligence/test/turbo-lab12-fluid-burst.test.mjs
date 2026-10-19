import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const path = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const cover = fs.readFileSync(path + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const cloud = fs.readFileSync(path + 'CloudMusicDiscovery.kt', 'utf8');
const nativeSearch = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt', 'utf8');
const source = fs.readFileSync(path + 'CoverDiscoverySources.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');

test('LAB12 search time strictly bounded at ten seconds from user action', () => {
  assert.match(cover,/TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(cover,/val turboCommandStartedAt = SystemClock.elapsedRealtime\(\)/);
  assert.match(cover,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
  assert.match(cover,/session\.turboFinished = true/);
  assert.match(cover,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover,/streamingVerifier\.cancel\(\)/);
});
test('LAB12 50 immutable posters published in one block with originals first', () => {
  assert.match(cover,/TURBO_RESULT_QUOTA = 50/);
  assert.match(cover,/TURBO_MIN_EARLY_POSTER_BLOCK = 50/);
  assert.match(cover,/fun turboRankedPool\(\)/);
  assert.match(cover,/sortGroup\(originalVariants, DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/sortGroup\(coverCandidatePool\(\), DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/publishedOriginalSnapshots = ranked\.filter\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/publishedCoverSnapshots = ranked\.filterNot\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/if \(publishedCoverSnapshots\.isEmpty\(\) && publishedOriginalSnapshots\.isEmpty\(\)\)/);
});
test('LAB12 do not confuse same audio on new album with new interpretation', () => {
  assert.match(cover,/fun isDistinctOriginalVersion/);
  assert.match(cover,/seed\.kind == DiscogsVersionKind\.LIVE/);
  assert.match(cover,/originalVariantMarker\.containsMatchIn\(description\)/);
  assert.match(cover,/val documented = seed\.workRelationConfirmed/);
  assert.match(cover,/if \(documented\) 20 else seed\.confidenceScore\.coerceAtMost\(17\)/);
});
test('LAB12 original work from archive, memory, and corroborated earliest disc issue', () => {
  assert.match(cover,/archiveOriginalArtist/);
  assert.match(cover,/memoryState\?\.discovery\?\.original\?\.artist/);
  assert.match(cover,/val earliest = merged\.filter/);
  assert.match(cover,/seed\.sourceNames\.distinct\(\)\.size >= 2/);
  assert.match(cover,/first\.year < second\.year/);
  assert.match(cover,/resolvedOriginalArtist = first\.artist/);
});
test('LAB12 native MusicLab search lane: parallel, bounded, no long chain', () => {
  assert.match(nativeSearch,/turboFastOnly: Boolean = false/);
  assert.match(nativeSearch,/private val turboSearchGate = Semaphore\(4\)/);
  assert.match(nativeSearch,/fastMusicLabRace\(track, chosen, excludedVideoIds\)/);
  assert.match(nativeSearch,/YouTube\.searchSummary\(query\)/);
  assert.match(nativeSearch,/YouTube\.search\(query, YouTube\.SearchFilter\.FILTER_SONG\)/);
  assert.match(cover,/turboFastOnly = isTurbo && mode == DiscogsDirectMode\.COVER/);
});
test('LAB12 protects playback: four video verifiers and no eager audio stream probes', () => {
  assert.match(cover,/val turboVideoCheckGate = remember\(sessionKey\) \{ Semaphore\(4\) \}/);
  assert.match(cover,/turboVideoCheckGate\.withPermit/);
  assert.doesNotMatch(cover,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover,/Video verificati:/);
});
test('LAB12 preserves archive, approvals, memory and source-specific backpressure', () => {
  assert.match(cloud,/suspend fun searchArchive\(/);
  assert.match(cloud,/suspend fun discoverMemoryState\(/);
  assert.match(cloud,/suspend fun saveBrainDecision\(/);
  assert.match(cloud,/suspend fun savePlaybackBinding\(/);
  assert.match(cover,/CloudMusicDiscovery\.cachedMemoryState\(/);
  assert.match(cover,/\.take\(24\)\.filter \{ sourceEvidence\.add\(it\) \}/);
  assert.match(source,/sourceGate = Semaphore\(maxNetworkFanOut\.coerceIn/);
  assert.match(cloud,/internal val turboReadOnly: Boolean/);
});
test('LAB12 immutable video updates, re-entry and explicit manual retry', () => {
  assert.match(cover,/fun videoBindingOnly\(poster: DiscogsVersionSeed\)/);
  assert.match(cover,/session\.publishedOriginalSnapshots = publishedOriginalSnapshots/);
  assert.match(cover,/session\.publishedCoverSnapshots = publishedCoverSnapshots/);
  assert.match(cover,/manualVideoSearchJob\?\.cancel\(\)/);
  assert.match(cover,/delay\(minOf\(30_000L, 2_000L \+ failedRounds \* 2_500L\)\)/);
});
test('LAB12 correct signed Turbo family, same certificate and next monotonic version', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1012"/);
  assert.match(env,/UAB_OUTPUT_NAME="MusicLab-Turbo-LAB12"/);
  assert.match(env,/musiclab-turbo-family-02-test-reuses-lab-test-key/);
  assert.match(env,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49/);
});
