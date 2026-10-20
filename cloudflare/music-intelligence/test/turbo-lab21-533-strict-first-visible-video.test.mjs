import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const ui = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const source = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt', 'utf8');
const cloud = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt', 'utf8');
const archive = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabArchiveScreen.kt', 'utf8');
const stamp = fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab21-env.py', 'utf8');

const c = ui.split('LaunchedEffect(sessionKey) {')[1].split('fun pauseCoverBackgroundForPlayback()')[0];

test('5+3+3 is exact and is the single canonical video budget', () => {
  assert.match(ui, /TURBO_PRIMARY_VIDEO_MS = 5_000L/);
  assert.match(ui, /TURBO_PRIMARY_GRACE_MS = 3_000L/);
  assert.match(ui, /TURBO_REPLACEMENT_VIDEO_MS = 3_000L/);
  assert.match(ui, /TURBO_AUTO_TOTAL_MS =\s+TURBO_PRIMARY_VIDEO_MS \+ TURBO_PRIMARY_GRACE_MS \+ TURBO_REPLACEMENT_VIDEO_MS/);
  assert.doesNotMatch(ui, /TURBO_VIDEO_SKIP_MS|TURBO_VIDEO_DEADLINE_MS/);
});

test('only actual first viewport appearance sets an immutable firstSeenAt', () => {
  assert.match(c, /snapshotFlow \{/);
  assert.match(c, /listState\.layoutInfo\.visibleItemsInfo/);
  assert.match(c, /session\.turboFirstVisibleAtMs\.containsKey\(fingerprint\)/);
  assert.match(c, /val firstSeenAt = SystemClock\.elapsedRealtime\(\)/);
  assert.match(c, /session\.turboFirstVisibleAtMs\[fingerprint\] = firstSeenAt/);
  assert.doesNotMatch(c, /session\.turboFirstVisibleAtMs\[fingerprint\] = SystemClock/);
});

test('primary network lookup is scheduled first and remains bounded', () => {
  assert.match(c, /val primaryEndsAt = firstSeenAt \+ TURBO_PRIMARY_VIDEO_MS/);
  assert.match(c, /val firstAttempt = async \{/);
  assert.match(c, /while \(isActive && rounds < 2 &&\s+SystemClock\.elapsedRealtime\(\) < primaryEndsAt\)/);
  assert.match(c, /resolveVideoChunk\(listOf\(actual\)\)/);
  assert.match(c, /withTimeoutOrNull\(firstRemaining\) \{ firstAttempt\.await\(\) \}/);
});

test('5-to-8s is a real wait for existing work only, with no new search', () => {
  assert.match(c, /val graceEndsAt = primaryEndsAt \+ TURBO_PRIMARY_GRACE_MS/);
  assert.match(c, /withTimeoutOrNull\(graceRemaining\) \{ firstAttempt\.await\(\) \}/);
  assert.match(c, /firstAttempt\.cancel\(\)/);
  assert.match(c, /if \(waitForReplacement > 0\) delay\(waitForReplacement\)/);
  assert.ok(c.indexOf('firstAttempt.cancel()') < c.indexOf('val budgetMs = autoEndsAt'));
});

test('8-to-11s uses one strictly bounded replacement search', () => {
  assert.match(c, /val autoEndsAt = graceEndsAt \+ TURBO_REPLACEMENT_VIDEO_MS/);
  assert.match(c, /val budgetMs = autoEndsAt - SystemClock\.elapsedRealtime\(\)/);
  assert.match(c, /if \(budgetMs <= 0\) return@launch/);
  assert.match(c, /withTimeoutOrNull\(budgetMs\) \{/);
  assert.match(c, /turboReplacementGate\.withPermit/);
  assert.match(ui, /val turboReplacementGate = remember\(sessionKey\) \{ Semaphore\(1\) \}/);
});

test('fallback does not replace the current work with an unrelated recording', () => {
  assert.match(c, /TitleMeaningResolver\.matchesBaseTitle\(/);
  assert.match(c, /spare\.workRelationConfirmed/);
  assert.match(c, /modeAcceptsSeed\(spare\)/);
  assert.match(c, /DiscogsVersionSource\.recordingIdentityKey\(spare\) !in occupied/);
  assert.match(c, /spare\.coverUrl != original\.coverUrl/);
  assert.match(c, /spare\.resolvedVideoId != original\.resolvedVideoId/);
  assert.match(c, /isDistinctOriginalVersion\(spare\) == originalSide/);
});

test('verified cached alternatives are consulted first without pointless new catalog scans', () => {
  assert.match(c, /session\.turboReserve \+ turboRankedPool\(\)/);
  assert.match(c, /compareByDescending<DiscogsVersionSeed> \{\s+hasPublishableVideo\(it\)/);
  assert.match(c, /turboReplacementClaims\.add\(spare\.fingerprint\)/);
  assert.doesNotMatch(c, /refillReserveOnce|CoverDiscoverySources\.discover/);
});

test('new fallback IDs require hard YouTube metadata compatibility', () => {
  assert.match(ui, /suspend fun lab21VerifyAlternative\(seed: DiscogsVersionSeed\)/);
  assert.match(ui, /YouTube\.queue\(videoIds = listOf\(id\)\)/);
  assert.match(ui, /CompilationTrackResolver\.resolveTrack\(/);
  assert.match(ui, /CompilationTrackResolver\.isHardCompatible\(track, candidate\)/);
  assert.match(ui, /saveMetadataVerifiedVideo\(/);
  assert.match(ui, /session\.playReadyVideoIds\.add\(candidate\.id\)/);
});

test('replacement updates exactly one complete row, no poster and video mismatch', () => {
  assert.match(c, /val change: \(DiscogsVersionSeed\) -> DiscogsVersionSeed = \{/);
  assert.match(c, /if \(it\.fingerprint == fingerprint\) verified else it/);
  assert.match(c, /session\.publishedCoverSnapshots = publishedCoverSnapshots/);
  assert.match(c, /session\.publishedOriginalSnapshots = publishedOriginalSnapshots/);
  assert.match(c, /session\.stableOrder = session\.stableOrder\.map/);
});

test('first visible deadline cannot reset after swap or scroll', () => {
  assert.match(c, /session\.turboFirstVisibleAtMs\[verified\.fingerprint\] = firstSeenAt/);
  assert.match(c, /session\.turboAutoClosedFingerprints\.add\(verified\.fingerprint\)/);
  assert.match(c, /session\.turboAutoClosedFingerprints\.add\(fingerprint\)/);
  assert.match(c, /session\.turboFirstVisibleAtMs\.containsKey\(fingerprint\)/);
});

test('old 10-second worker, automatic reentry retry and global verifier are removed', () => {
  assert.doesNotMatch(ui, /fun startTurboVideoBackground\(/);
  assert.doesNotMatch(ui, /startTurboVideoBackground\(\)/);
  assert.doesNotMatch(ui, /val streamingVerifier = launch/);
  assert.doesNotMatch(ui, /streamingVerifier\.cancel\(\)/);
  assert.doesNotMatch(ui, /val verifyFanOut =/);
});

test('all automatic row jobs are cancelled on new real search or screen exit', () => {
  assert.match(ui, /turboVisibleJobs\.values\.forEach \{ it\.cancel\(\) \}/);
  assert.match(ui, /session\.turboFirstVisibleAtMs\.clear\(\)/);
  assert.match(ui, /session\.turboAutoClosedFingerprints\.clear\(\)/);
  assert.match(ui, /turboReplacementClaims\.clear\(\)/);
  assert.match(c, /turboVisibleJobs\.remove\(fingerprint\)/);
});

test('manual retry remains explicit and preempts automatic work', () => {
  assert.match(ui, /fun retryMissingVideo\(seed: DiscogsVersionSeed\)/);
  assert.match(ui, /turboVisibleJobs\.remove\(seed\.fingerprint\)\?\.cancel\(\)/);
  assert.match(ui, /session\.turboAutoClosedFingerprints\.add\(seed\.fingerprint\)/);
});

test('archive-first link and artwork reuse from LAB20 are preserved', () => {
  assert.match(archive, /CloudMusicDiscovery\.rememberArchiveSelection\(item, endpoint\)/);
  assert.match(cloud, /fun recentArchiveSelection\(title: String, endpoint: String\)/);
  assert.match(ui, /val earlyArchiveSeeds = archiveSeeds\(listOfNotNull\(selectedArchiveItem\)\)/);
  assert.match(ui, /playbackVideoId = item\.playbackVideoId/);
  assert.match(ui, /coverUrl = item\.coverUrl/);
});

test('native playback engine is not accessed by the automatic coordinator', () => {
  assert.doesNotMatch(c, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.doesNotMatch(c, /resolveTrackAudio|prepareAudioStream/);
});

test('APK identity is LAB21 1021, same install family and no signing manipulation', () => {
  assert.match(stamp, /TURBO_LAB21/);
  assert.match(stamp, /MusicLab-Turbo-LAB21/);
  assert.match(stamp, /"1021"/);
  assert.match(stamp, /it\.verlezza\.musiclab\.turbo01/);
  assert.doesNotMatch(stamp, /keytool|newkey|jks\.b64"\s*:/);
});

test('manual mode appears only after 11 seconds, never on a verified video', () => {
  assert.match(ui, /fun manualSwapReady\(seed: DiscogsVersionSeed\)/);
  assert.match(ui, /seed\.fingerprint in session\.turboAutoClosedFingerprints/);
  assert.match(ui, /SystemClock\.elapsedRealtime\(\) - it >= TURBO_AUTO_TOTAL_MS/);
  assert.match(ui, /!hasPublishableVideo\(seed\)/);
  assert.match(ui, /automaticVideoManaged = isTurbo/);
  assert.match(ui, /manualSwitchAvailable = isTurbo && manualSwapReady\(seed\)/);
  assert.match(ui, /automaticVideoManaged && manualSwitchAvailable ->\s+"Video assente · Tocca per cambiare cover"/);
  assert.match(ui, /videoReadyVerified -> "Video trovato/);
});
test('manual tap never performs internet searches for the failed original card', () => {
  const manual = ui.split('fun retryMissingVideo(seed: DiscogsVersionSeed) {')[1].split('if (resolvingFingerprint == seed.fingerprint) return')[0];
  assert.match(manual, /val alternatives = session\.turboReserve/);
  assert.match(manual, /CoverPlaybackMemory\.metadataVerifiedVideo\(context, candidate\.fingerprint\)/);
  assert.match(manual, /val winner = replacement/);
  assert.match(manual, /if \(winner == null\) \{/);
  assert.doesNotMatch(manual, /resolveVideoChunk|CompilationTrackResolver\.resolveTrack|YouTube\.queue|CloudMusicDiscovery\.searchArchive/);
  assert.doesNotMatch(manual, /while \(isActive|delay\(/);
});
test('manual replacement rotates distinct preloaded verified covers, no repeated candidates', () => {
  const manual = ui.split('fun retryMissingVideo(seed: DiscogsVersionSeed) {')[1].split('if (resolvingFingerprint == seed.fingerprint) return')[0];
  assert.match(manual, /session\.turboManualTriedSpareIds/);
  assert.match(manual, /candidate\.fingerprint !in tried/);
  assert.match(manual, /tried\.add\(candidate\.fingerprint\)/);
  assert.match(manual, /hasPublishableVideo\(ready\)/);
  assert.match(manual, /ready\.resolvedVideoId in usedIds/);
  assert.match(manual, /DiscogsVersionSource\.recordingIdentityKey\(candidate\) !in occupied/);
  assert.match(manual, /freshArtwork == current\.coverUrl/);
  assert.match(manual, /winner\.fingerprint/);
});
test('manual outcome updates entire poster, ID and source atomically or leaves card intact', () => {
  const manual = ui.split('fun retryMissingVideo(seed: DiscogsVersionSeed) {')[1].split('if (resolvingFingerprint == seed.fingerprint) return')[0];
  assert.match(manual, /val change: \(DiscogsVersionSeed\) -> DiscogsVersionSeed = \{/);
  assert.match(manual, /publishedCoverSnapshots = publishedCoverSnapshots\.map\(change\)/);
  assert.match(manual, /publishedOriginalSnapshots = publishedOriginalSnapshots\.map\(change\)/);
  assert.match(manual, /results = results\.map\(change\)/);
  assert.match(manual, /session\.stableOrder = session\.stableOrder\.map/);
  assert.match(manual, /session\.turboAutoClosedFingerprints\.add\(winner\.fingerprint\)/);
  assert.match(manual, /"Nessun'altra cover con video già pronto\."/);
});
test('scroll does not cancel an active 5+3+3 timer and manual swap does not restart auto', () => {
  assert.match(c, /\}\.collect \{ visible ->/);
  assert.doesNotMatch(c, /\}\.collectLatest \{ visible ->/);
  assert.match(ui, /session\.turboManualTriedSpareIds\.clear\(\)/);
  assert.match(ui, /turboAutoClosedTick\+\+/);
  assert.match(ui, /Modifier\.clickable\(onClick = onRetryVideo\)/);
  assert.match(ui, /automaticVideoManaged\) manualSwitchAvailable/);
});
