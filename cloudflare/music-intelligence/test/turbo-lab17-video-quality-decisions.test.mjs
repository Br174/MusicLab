import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const path = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const cover = fs.readFileSync(path + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const cloud = fs.readFileSync(path + 'CloudMusicDiscovery.kt', 'utf8');
const nativeSearch = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt', 'utf8');
const source = fs.readFileSync(path + 'CoverDiscoverySources.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');

test('LAB17 search time strictly bounded at ten seconds from user action', () => {
  assert.match(cover,/TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(cover,/val turboCommandStartedAt = SystemClock.elapsedRealtime\(\)/);
  assert.match(cover,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
  assert.match(cover,/session\.turboFinished = true/);
  assert.match(cover,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover,/streamingVerifier\.cancel\(\)/);
});
test('LAB17 25+25 immutable posters, originals first in initial prefix', () => {
  assert.match(cover,/TURBO_RESULT_QUOTA = 50/);
  assert.match(cover,/TURBO_MIN_EARLY_POSTER_BLOCK = 25/);
  assert.match(cover,/TURBO_POSTER_SECOND_BLOCK = 25/);
  assert.match(cover,/TURBO_FIRST_POSTER_TARGET_MS = 5_000L/);
  assert.match(cover,/fun turboRankedPool\(\)/);
  assert.match(cover,/sortGroup\(originalVariants, DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/sortGroup\(coverCandidatePool\(\), DirectVersionSort.RELEVANCE\)/);
  assert.match(cover,/publishedOriginalSnapshots = firstBlock\.filter\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/publishedCoverSnapshots = firstBlock\.filterNot\(::isDistinctOriginalVersion\)/);
  assert.match(cover,/if \(publishedCoverSnapshots\.isEmpty\(\) && publishedOriginalSnapshots\.isEmpty\(\)\)/);
});
test('LAB17 do not confuse same audio on new album with new interpretation', () => {
  assert.match(cover,/fun isDistinctOriginalVersion/);
  assert.match(cover,/seed\.kind == DiscogsVersionKind\.LIVE/);
  assert.match(cover,/originalVariantMarker\.containsMatchIn\(description\)/);
  assert.match(cover,/val documented = seed\.workRelationConfirmed/);
  assert.match(cover,/if \(documented\) 20 else seed\.confidenceScore\.coerceAtMost\(17\)/);
});
test('LAB17 original work from archive, memory, and corroborated earliest disc issue', () => {
  assert.match(cover,/archiveOriginalArtist/);
  assert.match(cover,/memoryState\?\.discovery\?\.original\?\.artist/);
  assert.match(cover,/val earliest = merged\.filter/);
  assert.match(cover,/seed\.sourceNames\.distinct\(\)\.size >= 2/);
  assert.match(cover,/first\.year < second\.year/);
  assert.match(cover,/resolvedOriginalArtist = first\.artist/);
});
test('LAB17 native MusicLab search lane: parallel, bounded, no long chain', () => {
  assert.match(nativeSearch,/turboFastOnly: Boolean = false/);
  assert.match(nativeSearch,/private val turboSearchGate = Semaphore\(4\)/);
  assert.match(nativeSearch,/fastMusicLabRace\(track, chosen, excludedVideoIds, turboWindow = true\)/);
  assert.match(nativeSearch,/YouTube\.searchSummary\(query\)/);
  assert.match(nativeSearch,/YouTube\.search\(query, YouTube\.SearchFilter\.FILTER_SONG\)/);
  assert.match(cover,/turboFastOnly = isTurbo && mode == DiscogsDirectMode\.COVER/);
});
test('LAB17 protects playback: four video verifiers and no eager audio stream probes', () => {
  assert.match(cover,/val turboVideoCheckGate = remember\(sessionKey\) \{ Semaphore\(4\) \}/);
  assert.match(cover,/turboVideoCheckGate\.withPermit/);
  assert.doesNotMatch(cover,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover,/Video verificati:/);
});
test('LAB17 preserves archive, approvals, memory and source-specific backpressure', () => {
  assert.match(cloud,/suspend fun searchArchive\(/);
  assert.match(cloud,/suspend fun discoverMemoryState\(/);
  assert.match(cloud,/suspend fun saveBrainDecision\(/);
  assert.match(cloud,/suspend fun savePlaybackBinding\(/);
  assert.match(cover,/CloudMusicDiscovery\.cachedMemoryState\(/);
  assert.match(cover,/\.take\(24\)\.filter \{ sourceEvidence\.add\(it\) \}/);
  assert.match(source,/sourceGate = Semaphore\(maxNetworkFanOut\.coerceIn/);
  assert.match(cloud,/internal val turboReadOnly: Boolean/);
});
test('LAB17 immutable video updates, re-entry and explicit manual retry', () => {
  assert.match(cover,/fun videoBindingOnly\(poster: DiscogsVersionSeed\)/);
  assert.match(cover,/session\.publishedOriginalSnapshots = publishedOriginalSnapshots/);
  assert.match(cover,/session\.publishedCoverSnapshots = publishedCoverSnapshots/);
  assert.match(cover,/manualVideoSearchJob\?\.cancel\(\)/);
  assert.match(cover,/delay\(minOf\(30_000L, 2_000L \+ failedRounds \* 2_500L\)\)/);
});
test('LAB17 correct signed Turbo family, same certificate and next monotonic version', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1017"/);
  assert.match(env,/UAB_OUTPUT_NAME="MusicLab-Turbo-LAB17"/);
  assert.match(env,/musiclab-turbo-family-02-test-reuses-lab-test-key/);
  assert.match(env,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49/);
});

test('LAB17 archive: an approved archive-only video can supply a poster without rewriting editorial cache', () => {
  assert.match(cover, /CoverPlaybackMemory\.editorialArtwork\(context, seed\.fingerprint, seed\.coverUrl\)/);
  assert.match(cover, /"Archivio Cloud" in seed\.sourceNames/);
  assert.match(cover, /https:\/\/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg/);
  assert.match(cover, /workRelationConfirmed = true/);
});
test('LAB17 archive/memory reconcile from original-work attribution', () => {
  assert.match(cover, /val archiveArtist = withTimeoutOrNull\(800L\)/);
  assert.match(cover, /archiveDeferred\.await\(\)/);
  assert.match(cover, /val memoryArtist = archiveArtist/);
  assert.match(cover, /artist = memoryArtist, config = config/);
});
test('LAB17 video: a single native search is allowed to finish before the group is cancelled', () => {
  assert.match(cover, /withTimeoutOrNull\(minOf\(remaining, 3_450L\)\)/);
  assert.doesNotMatch(cover, /withTimeoutOrNull\(minOf\(remaining, 1_250L\)\)/);
  assert.match(nativeSearch, /if \(turboWindow\) 2_250L else 3_050L/);
  assert.match(nativeSearch, /FILTER_VIDEO/);
  assert.match(nativeSearch, /"MusicLab · video rapido"/);
  assert.match(cover, /TURBO_BURST_DEADLINE_MS = 10_000L/);
});
test('LAB17 audio: background metadata is bounded and never opens 50 player streams', () => {
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
  assert.doesNotMatch(cover, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover, /val turboVideoCheckGate = remember\(sessionKey\) \{ Semaphore\(4\) \}/);
  assert.match(cover, /streamingVerifier\.cancel\(\)/);
  assert.match(cover, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover, /playerConnection\?\.service\?\.setCoverPerformanceLoad\(/);
  assert.match(service, /lab62BufferingCount/);
  assert.match(service, /setCoverPerformanceLoad\(active: Boolean, heavy: Boolean\)/);
});
test('LAB17 after-burst state only retains selected cards instead of all discovered records', () => {
  assert.match(cover, /val frozenIds = \(publishedOriginalSnapshots \+ publishedCoverSnapshots\)/);
  assert.match(cover, /val frozenResults = results\.filter \{ it\.fingerprint in frozenIds \}/);
  assert.match(cover, /session\.results = frozenResults/);
  assert.match(cover, /session\.turboFinished = true/);
});

test('LAB17 original artist videos and archived legacy thumbnails are reachable without altering the player', () => {
  assert.match(cover, /val published = publishedOriginalSnapshots \+ publishedCoverSnapshots/);
  assert.match(cover, /val admitted = published\.any \{ it\.fingerprint == selectedFingerprint \}/);
  assert.match(cover, /published\.firstOrNull \{/);
  assert.doesNotMatch(cover, /val admitted = publishedCoverSnapshots\.any \{/);
  assert.match(cover, /url\.contains\("ytimg\.com", ignoreCase = true\)/);
  assert.match(cover, /seed\.resolvedVideoId\?\.takeIf/);
});

test('LAB17 separates video completion from ten second poster discovery', () => {
  assert.match(cover, /TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(cover, /startTurboVideoBackground\(\)/);
  assert.match(cover, /session\.turboReserve = turboRankedPool\(\)/);
  assert.match(cover, /session\.turboFinished = true/);
  assert.match(cover, /session\.turboVideoComplete = true/);
  assert.match(cover, /if \(session\.turboFinished &&/);
});
test('LAB17 videos prioritize row positions and have bounded concurrent searches', () => {
  assert.match(cover, /val positions = \(publishedOriginalSnapshots \+ publishedCoverSnapshots\)/);
  assert.match(cover, /val gate = Semaphore\(TURBO_BACKGROUND_PARALLELISM\)/);
  assert.match(cover, /positions\.map \{ fingerprint ->/);
  assert.match(cover, /gate\.withPermit \{/);
  assert.match(cover, /TURBO_BACKGROUND_PARALLELISM = 3/);
  assert.match(cover, /withTimeoutOrNull\(4_200L\)/);
});
test('LAB17 switches to an alternate recording after 25s without changing position', () => {
  assert.match(cover, /TURBO_VIDEO_SKIP_MS = 25_000L/);
  assert.match(cover, /SystemClock\.elapsedRealtime\(\) - firstShownAtMs < TURBO_VIDEO_SKIP_MS/);
  assert.match(cover, /isDistinctOriginalVersion\(it\) == originalSide/);
  assert.match(cover, /kotlin\.math\.abs\(it\.confidenceScore - old\.confidenceScore\)/);
  assert.match(cover, /val verified = gate\.withPermit \{/);
  assert.match(cover, /verifyReserve\(spare\)/);
  assert.match(cover, /publishedCoverSnapshots = publishedCoverSnapshots\.map\(change\)/);
  assert.match(cover, /publishedOriginalSnapshots\s*=\s*publishedOriginalSnapshots\.map\(change\)/);
  assert.match(cover, /session\.stableOrder = session\.stableOrder\.map/);
});
test('LAB17 no holes, no false video, no video swap during manual play', () => {
  assert.match(cover, /if \(hasPublishableVideo\(latest\)\) return@launch/);
  assert.match(cover, /if \(selectedFingerprint == fingerprint &&/);
  assert.match(cover, /resolvingFingerprint == fingerprint/);
  assert.match(cover, /isHardCompatible\(track, song\)/);
  assert.match(cover, /CoverPlaybackMemory\.saveMetadataVerifiedVideo/);
  assert.match(cover, /session\.turboReserve = session\.turboReserve\.filterNot/);
});
test('LAB17 protects native buffer without changing playback engine', () => {
  const nativePlayer = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt','utf8');
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
  assert.match(cover, /playerConnection\?\.service\?\.isCoverAudioAtRisk\(\)/);
  assert.match(cover, /while \(isActive && isAudioProtected\(\)\) delay\(200L\)/);
  assert.match(service, /fun isCoverAudioAtRisk\(\): Boolean/);
  assert.match(service, /player\.playbackState == Player\.STATE_BUFFERING/);
  assert.match(nativePlayer, /bundle\(\)\.extractor\.extract\(/);
  assert.doesNotMatch(cover, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
});
test('LAB17 lifecycle cancels background workers and keeps manual recovery', () => {
  assert.match(cover, /turboVideoJob\?\.cancel\(\)/);
  assert.match(cover, /manualVideoSearchJob\?\.cancel\(\)/);
  assert.match(cover, /CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(cover, /TURBO_RESERVE_MAX = 100/);
  assert.match(cover, /CoverPlaybackMemory\.stableArtwork\(context, seed\.fingerprint, seed\.coverUrl\)/);
});

test('LAB17 audio-first guard preserves short native startup and fixed 50s maximum cache', () => {
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 900/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 4_000/);
  assert.match(service, /setBufferDurationsMs\(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS\)/);
  assert.match(service, /PLAYBACK_PRIORITY_BURST_MAX_MS = 15_000L/);
  assert.match(service, /PLAYBACK_PRIORITY_RELEASE_BUFFER_MS = 12_000L/);
  assert.match(service, /COVER_AUDIO_SAFETY_BUFFER_MS = 12_000L/);
  assert.match(cover, /if \(isTurbo && mode == DiscogsDirectMode.COVER &&\s+playerConnection\?\.service\?\.isCoverAudioAtRisk\(\) == true\) return@coroutineScope/);
});
test('LAB17 first 25 arrive before global 10s seal when available', () => {
  assert.match(cover, /TURBO_FIRST_POSTER_DEADLINE_MS = 1_200L/);
  assert.match(cover, /ranked\.size < TURBO_MIN_EARLY_POSTER_BLOCK/);
  assert.match(cover, /val firstBlock = ranked\.take\(TURBO_MIN_EARLY_POSTER_BLOCK\)/);
  assert.match(cover, /loading = false/);
  assert.match(cover, /firstBlock\.forEach/);
});
test('LAB17 second 25 append only after five seconds and remain anchored', () => {
  assert.match(cover, /fun sealSecondPostersOnce\(\)/);
  assert.match(cover, /sealSecondPostersOnce\(\)/);
  assert.match(cover, /TURBO_FIRST_POSTER_TARGET_MS/);
  assert.match(cover, /if \(next\.size < TURBO_POSTER_SECOND_BLOCK\) return/);
  assert.match(cover, /publishedCoverSnapshots = publishedCoverSnapshots \+ next/);
  assert.match(cover, /publishedCoverSnapshots = publishedCoverSnapshots \+ secondBlock/);
  assert.match(cover, /TURBO_RESULT_QUOTA -/);
  assert.match(cover, /session\.turboPosterPublishedAtMs\.putIfAbsent/);
});
test('LAB17 Smart Skip uses real visible wall-clock and fair per-query permits', () => {
  assert.match(cover, /val firstShownAtMs = session\.turboPosterPublishedAtMs\[fingerprint\]/);
  assert.match(cover, /SystemClock\.elapsedRealtime\(\) - firstShownAtMs < TURBO_VIDEO_SKIP_MS/);
  assert.match(cover, /val gate = Semaphore\(TURBO_BACKGROUND_PARALLELISM\)/);
  assert.match(cover, /gate\.withPermit \{\s+if \(!isAudioProtected\(\)\)/);
  assert.match(cover, /val verified = gate\.withPermit \{/);
  assert.doesNotMatch(cover, /eligibleMs \+=/);
  assert.match(cover, /if \(hasPublishableVideo\(latest\)\) return@launch/);
});
test('LAB17 keeps native YouTube resolver, Cloud, player metadata and signing family', () => {
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
  assert.match(service,/private fun createDataSourceFactory\(/);
  assert.match(service,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(cover,/session\.turboReserve = turboRankedPool\(\)/);
  assert.match(cover,/session\.stableOrder = session\.stableOrder\.map/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1017"/);
  assert.match(env,/musiclab-turbo-family-02-test-reuses-lab-test-key/);
});

test('LAB17 poster collector owns source ingestion independent of video verifier', () => {
  assert.match(cover, /val posterCollector = launch \{/);
  assert.match(cover, /val streamingVerifier = launch \{/);
  assert.match(cover, /posterCollector\.cancel\(\)/);
  const collector = cover.split('val posterCollector = launch {')[1].split('val streamingVerifier = launch {')[0];
  const video = cover.split('val streamingVerifier = launch {')[1].split('val archiveDeferred = async')[0];
  assert.match(collector, /streamingCandidates\.receive\(\)/);
  assert.match(collector, /supplementalSeeds\.tryReceive\(\)/);
  assert.match(collector, /sealPostersOnce\(\)/);
  assert.match(collector, /sealSecondPostersOnce\(\)/);
  assert.doesNotMatch(collector, /resolveVideoChunk\(/);
  assert.doesNotMatch(video, /streamingCandidates\.receive\(\)/);
  assert.match(video, /resolveVideoChunk\(pending\)/);
  assert.match(video, /withTimeoutOrNull\(minOf\(remaining, 3_450L\)\)/);
});
test('LAB17 audio timing instrumentation cannot mutate native stream selection', () => {
  const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
  assert.match(service, /MusicLabLAB16Audio/);
  assert.match(service, /stream_source=REMOTE resolve_ms=%d success=%b cover_active=%b/);
  assert.match(service, /state=READY tap_to_ready_ms=%d buffered_ms=%d cover_active=%b/);
  assert.match(service, /state=BUFFERING buffered_ms=%d cover_active=%b priority=%b/);
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 900/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 4_000/);
  assert.match(service, /setBufferDurationsMs\(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS\)/);
  assert.match(service, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
});
test('LAB17 pinned r37 satellite matches central release without touching playback engine', () => {
  const capsule = fs.readFileSync('.motorlab/MOTORLAB_BOOT_CAPSULE.txt','utf8');
  const local = fs.readFileSync('.motorlab/MOTORLAB_LOCAL_CORE.txt','utf8');
  const sync = fs.readFileSync('.motorlab/MOTORLAB_SYNC_STATE.txt','utf8');
  assert.match(capsule, /^RELEASE=2026\.10\.14-r37$/m);
  assert.match(local, /^RELEASE=2026\.10\.14-r37$/m);
  assert.match(sync, /^LOCAL_RELEASE=2026\.10\.14-r37$/m);
  assert.match(sync, /^STATE=VERIFIED$/m);
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="1017"/);
});

test('LAB17 skips to a DIFFERENT fully verified recording rather than just rebinding video', () => {
  assert.match(cover, /val change: \(DiscogsVersionSeed\) -> DiscogsVersionSeed/);
  assert.match(cover, /if \(it\.fingerprint == fingerprint\) verified else it/);
  assert.match(cover, /val verified = gate\.withPermit \{/);
  assert.match(cover, /verifyReserve\(spare\)/);
  assert.match(cover, /if \(hasPublishableVideo\(latest\)\) return@launch/);
  assert.match(cover, /session\.stableOrder = session\.stableOrder\.map/);
  assert.match(cover, /session\.turboPosterPublishedAtMs\.remove\(fingerprint\)/);
  assert.match(cover, /session\.turboPosterPublishedAtMs\[verified\.fingerprint\]/);
  assert.match(cover, /session\.turboReserve = session\.turboReserve\.filterNot/);
});
test('LAB17 spare candidate preserves existing reserve IDs but not visible video duplicates', () => {
  const resolve = cover.split('suspend fun verifyReserve(seed: DiscogsVersionSeed)')[1].split('suspend fun isAudioProtected')[0];
  // Helper was intentionally lifted before verifyReserve, so delimit with try block.
  assert.match(resolve, /val visibleIds = \(publishedOriginalSnapshots \+ publishedCoverSnapshots\)/);
  assert.match(resolve, /storedId !in visibleIds/);
  assert.match(resolve, /song\.id in visibleIds/);
  assert.doesNotMatch(resolve, /song\.id in session\.usedVideoIds/);
  assert.match(resolve, /rejectedVideoIds\(context, seed\.fingerprint\)/);
  assert.match(resolve, /saveMetadataVerifiedVideo\(/);
  assert.match(resolve, /isHardCompatible\(track, song\)/);
  assert.match(resolve, /val verifiedKnown = known\?\.takeIf/);
  assert.match(resolve, /val fromSearch = if \(verifiedKnown == null\)/);
});
test('LAB17 if reserve is empty run one bounded new discovery not another entire poster burst', () => {
  assert.match(cover, /var refill: kotlinx\.coroutines\.Deferred<List<CoverSourceCandidate>>\? = null/);
  assert.match(cover, /val request = refill \?: refillScope\.async\(Dispatchers\.IO\)/);
  assert.match(cover, /withTimeoutOrNull\(7_000L\)/);
  assert.match(cover, /CoverDiscoverySources\.discover\(/);
  assert.match(cover, /maxNetworkFanOut = 1/);
  assert.match(cover, /rankEligibleSpare\(refillReserveOnce\(\)\)/);
  assert.match(cover, /\.distinctBy\(DiscogsVersionSource::recordingIdentityKey\)/);
  assert.match(cover, /\.take\(TURBO_RESERVE_MAX\)/);
});
test('LAB17 replacement is distinct, similar score, bounded and respects audio', () => {
  assert.match(cover, /DiscogsVersionSource\.recordingIdentityKey\(spare\) !in chosenIdentities/);
  assert.match(cover, /!isRejected\(spare\)/);
  assert.match(cover, /isDistinctOriginalVersion\(spare\)/);
  assert.match(cover, /kotlin\.math\.abs\(it\.confidenceScore - old\.confidenceScore\)/);
  assert.match(cover, /for \(spare in reserve\.take\(8\)\)/);
  assert.match(cover, /while \(isActive && isAudioProtected\(\)\) delay\(200L\)/);
  assert.match(cover, /if \(selectedFingerprint == fingerprint &&\s+playbackIsNormallyPlaying\(\)\) return@launch/);
  assert.match(cover, /TURBO_VIDEO_SKIP_MS = 25_000L/);
});
test('LAB17 auto video discovery is not limited to two checks or to manual tap', () => {
  assert.match(cover, /if \(attempts < 4\)/);
  assert.match(cover, /TURBO_BACKGROUND_PARALLELISM = 3/);
  assert.match(cover, /session\.turboFinished &&\s+\(publishedCoverSnapshots \+ publishedOriginalSnapshots\)\.any/);
  assert.match(cover, /startTurboVideoBackground\(\)/);
});
test('LAB17 video display does not advertise perfect quality without video proof', () => {
  assert.match(cover, /fun videoCalibratedDisplaySeed\(seed: DiscogsVersionSeed\)/);
  assert.match(cover, /match == null && hasPublishableVideo\(seed\) -> 15/);
  assert.match(cover, /match == null -> 12/);
  assert.match(cover, /CompilationTrackResolver\.scoreSong\(track, it\)/);
  assert.match(cover, /isHardCompatible\(track, it\)/);
  assert.match(cover, /minOf\(raw, verifiedCeiling\)/);
  assert.match(cover, /\.map\(::videoCalibratedDisplaySeed\)/);
});
test('LAB17 Approva/Disapprova works offline with durable per-work local decisions', () => {
  const memory = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt','utf8');
  assert.match(memory,/fun editorialDecision\(context: Context, workKey: String, candidateKey: String\)/);
  assert.match(memory,/fun saveEditorialDecision\(/);
  assert.match(cover,/CoverPlaybackMemory\.saveEditorialDecision\(/);
  assert.match(cover,/localEditorialDecision\(seed\)/);
  assert.match(cover,/status == AiBrainDecisionStatus\.APPROVED/);
  assert.match(cover,/status != AiBrainDecisionStatus\.REJECTED/);
  const save=cover.split('fun saveDecision(seed: DiscogsVersionSeed, status: AiBrainDecisionStatus)')[1].split('// LAB13: retain a genuine editorial image')[0];
  assert.ok(save.indexOf('saveEditorialDecision(')<save.indexOf('CloudMusicDiscovery.saveBrainDecision('));
  assert.match(save,/detailSeed = null/);
  assert.match(save,/config != null && config\.cloudEndpoint\.isNotBlank\(\)/);
});
test('LAB17 preserves signed identity, native audio and independent 25+25 publication', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1017"/);
  assert.match(env,/UAB_OUTPUT_NAME="MusicLab-Turbo-LAB17"/);
  assert.match(cover,/val posterCollector = launch \{/);
  assert.match(cover,/val streamingVerifier = launch \{/);
  assert.match(cover,/publishedCoverSnapshots = publishedCoverSnapshots \+ next/);
  assert.doesNotMatch(cover,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
});
