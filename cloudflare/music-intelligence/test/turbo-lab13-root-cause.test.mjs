import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const path = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const cover = fs.readFileSync(path + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const cloud = fs.readFileSync(path + 'CloudMusicDiscovery.kt', 'utf8');
const nativeSearch = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt', 'utf8');
const source = fs.readFileSync(path + 'CoverDiscoverySources.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');

test('LAB13 search time strictly bounded at ten seconds from user action', () => {
  assert.match(cover,/TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(cover,/val turboCommandStartedAt = SystemClock.elapsedRealtime\(\)/);
  assert.match(cover,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
  assert.match(cover,/session\.turboFinished = true/);
  assert.match(cover,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover,/streamingVerifier\.cancel\(\)/);
});
test('LAB13 50 immutable posters published in one block with originals first', () => {
  assert.match(cover,/TURBO_RESULT_QUOTA = 50/);
  assert.match(cover,/TURBO_MIN_EARLY_POSTER_BLOCK = 50/);
  assert.match(cover,/fun turboRankedPool\(\)/);
  assert.match(cover,/sortGroup\(originalVariants, DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/sortGroup\(coverCandidatePool\(\), DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/publishedOriginalSnapshots = ranked\.filter\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/publishedCoverSnapshots = ranked\.filterNot\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/if \(publishedCoverSnapshots\.isEmpty\(\) && publishedOriginalSnapshots\.isEmpty\(\)\)/);
});
test('LAB13 do not confuse same audio on new album with new interpretation', () => {
  assert.match(cover,/fun isDistinctOriginalVersion/);
  assert.match(cover,/seed\.kind == DiscogsVersionKind\.LIVE/);
  assert.match(cover,/originalVariantMarker\.containsMatchIn\(description\)/);
  assert.match(cover,/val documented = seed\.workRelationConfirmed/);
  assert.match(cover,/if \(documented\) 20 else seed\.confidenceScore\.coerceAtMost\(17\)/);
});
test('LAB13 original work from archive, memory, and corroborated earliest disc issue', () => {
  assert.match(cover,/archiveOriginalArtist/);
  assert.match(cover,/memoryState\?\.discovery\?\.original\?\.artist/);
  assert.match(cover,/val earliest = merged\.filter/);
  assert.match(cover,/seed\.sourceNames\.distinct\(\)\.size >= 2/);
  assert.match(cover,/first\.year < second\.year/);
  assert.match(cover,/resolvedOriginalArtist = first\.artist/);
});
test('LAB13 native MusicLab search lane: parallel, bounded, no long chain', () => {
  assert.match(nativeSearch,/turboFastOnly: Boolean = false/);
  assert.match(nativeSearch,/private val turboSearchGate = Semaphore\(4\)/);
  assert.match(nativeSearch,/fastMusicLabRace\(track, chosen, excludedVideoIds, turboWindow = true\)/);
  assert.match(nativeSearch,/YouTube\.searchSummary\(query\)/);
  assert.match(nativeSearch,/YouTube\.search\(query, YouTube\.SearchFilter\.FILTER_SONG\)/);
  assert.match(cover,/turboFastOnly = isTurbo && mode == DiscogsDirectMode\.COVER/);
});
test('LAB13 protects playback: four video verifiers and no eager audio stream probes', () => {
  assert.match(cover,/val turboVideoCheckGate = remember\(sessionKey\) \{ Semaphore\(4\) \}/);
  assert.match(cover,/turboVideoCheckGate\.withPermit/);
  assert.doesNotMatch(cover,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover,/Video verificati:/);
});
test('LAB13 preserves archive, approvals, memory and source-specific backpressure', () => {
  assert.match(cloud,/suspend fun searchArchive\(/);
  assert.match(cloud,/suspend fun discoverMemoryState\(/);
  assert.match(cloud,/suspend fun saveBrainDecision\(/);
  assert.match(cloud,/suspend fun savePlaybackBinding\(/);
  assert.match(cover,/CloudMusicDiscovery\.cachedMemoryState\(/);
  assert.match(cover,/\.take\(24\)\.filter \{ sourceEvidence\.add\(it\) \}/);
  assert.match(source,/sourceGate = Semaphore\(maxNetworkFanOut\.coerceIn/);
  assert.match(cloud,/internal val turboReadOnly: Boolean/);
});
test('LAB13 immutable video updates, re-entry and explicit manual retry', () => {
  assert.match(cover,/fun videoBindingOnly\(poster: DiscogsVersionSeed\)/);
  assert.match(cover,/session\.publishedOriginalSnapshots = publishedOriginalSnapshots/);
  assert.match(cover,/session\.publishedCoverSnapshots = publishedCoverSnapshots/);
  assert.match(cover,/manualVideoSearchJob\?\.cancel\(\)/);
  assert.match(cover,/delay\(minOf\(30_000L, 2_000L \+ failedRounds \* 2_500L\)\)/);
});
test('LAB13 correct signed Turbo family, same certificate and next monotonic version', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1013"/);
  assert.match(env,/UAB_OUTPUT_NAME="MusicLab-Turbo-LAB13"/);
  assert.match(env,/musiclab-turbo-family-02-test-reuses-lab-test-key/);
  assert.match(env,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49/);
});

test('LAB13 archive: an approved archive-only video can supply a poster without rewriting editorial cache', () => {
  assert.match(cover, /CoverPlaybackMemory\.editorialArtwork\(context, seed\.fingerprint, seed\.coverUrl\)/);
  assert.match(cover, /"Archivio Cloud" in seed\.sourceNames/);
  assert.match(cover, /https:\/\/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg/);
  assert.match(cover, /workRelationConfirmed = true/);
});
test('LAB13 archive/memory reconcile from original-work attribution', () => {
  assert.match(cover, /val archiveArtist = withTimeoutOrNull\(800L\)/);
  assert.match(cover, /archiveDeferred\.await\(\)/);
  assert.match(cover, /val memoryArtist = archiveArtist/);
  assert.match(cover, /artist = memoryArtist, config = config/);
});
test('LAB13 video: a single native search is allowed to finish before the group is cancelled', () => {
  assert.match(cover, /withTimeoutOrNull\(minOf\(remaining, 3_450L\)\)/);
  assert.doesNotMatch(cover, /withTimeoutOrNull\(minOf\(remaining, 1_250L\)\)/);
  assert.match(nativeSearch, /if \(turboWindow\) 2_250L else 3_050L/);
  assert.match(nativeSearch, /FILTER_VIDEO/);
  assert.match(nativeSearch, /"MusicLab · video rapido"/);
  assert.match(cover, /TURBO_BURST_DEADLINE_MS = 10_000L/);
});
test('LAB13 audio: background metadata is bounded and never opens 50 player streams', () => {
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
  assert.doesNotMatch(cover, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover, /val turboVideoCheckGate = remember\(sessionKey\) \{ Semaphore\(4\) \}/);
  assert.match(cover, /streamingVerifier\.cancel\(\)/);
  assert.match(cover, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover, /playerConnection\?\.service\?\.setCoverPerformanceLoad\(/);
  assert.match(service, /lab62BufferingCount/);
  assert.match(service, /setCoverPerformanceLoad\(active: Boolean, heavy: Boolean\)/);
});
test('LAB13 after-burst state only retains selected cards instead of all discovered records', () => {
  assert.match(cover, /val frozenIds = \(publishedOriginalSnapshots \+ publishedCoverSnapshots\)/);
  assert.match(cover, /val frozenResults = results\.filter \{ it\.fingerprint in frozenIds \}/);
  assert.match(cover, /session\.results = frozenResults/);
  assert.match(cover, /session\.turboFinished = true/);
});

test('LAB13 original artist videos and archived legacy thumbnails are reachable without altering the player', () => {
  assert.match(cover, /val published = publishedOriginalSnapshots \+ publishedCoverSnapshots/);
  assert.match(cover, /val admitted = published\.any \{ it\.fingerprint == selectedFingerprint \}/);
  assert.match(cover, /published\.firstOrNull \{/);
  assert.doesNotMatch(cover, /val admitted = publishedCoverSnapshots\.any \{/);
  assert.match(cover, /url\.contains\("ytimg\.com", ignoreCase = true\)/);
  assert.match(cover, /seed\.resolvedVideoId\?\.takeIf/);
});
