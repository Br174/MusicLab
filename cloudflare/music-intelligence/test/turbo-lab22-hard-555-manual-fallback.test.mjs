import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const ui=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab22-env.py','utf8');
const archive=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabArchiveScreen.kt','utf8');
const first=ui.split('LaunchedEffect(sessionKey) {')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const manual=ui.split('fun retryMissingVideo(seed: DiscogsVersionSeed) {')[1].split('if (resolvingFingerprint == seed.fingerprint) return')[0];
const primary=ui.split('suspend fun resolveVideoChunk(chunk: List<DiscogsVersionSeed>)')[1].split('// LAB08 Pollicino')[0];

test('LAB22 exactly 5+5+5 and no old 5+3+3 or ten-second replacement',()=>{
  assert.match(ui,/TURBO_PRIMARY_VIDEO_MS = 5_000L/);
  assert.match(ui,/TURBO_PRIMARY_GRACE_MS = 5_000L/);
  assert.match(ui,/TURBO_REPLACEMENT_VIDEO_MS = 5_000L/);
  assert.match(ui,/TURBO_AUTO_TOTAL_MS =\s+TURBO_PRIMARY_VIDEO_MS \+ TURBO_PRIMARY_GRACE_MS \+ TURBO_REPLACEMENT_VIDEO_MS/);
  assert.doesNotMatch(ui,/TURBO_VIDEO_SKIP_MS|TURBO_VIDEO_DEADLINE_MS|5\+3\+3/);
});
test('first viewport appearance starts an immutable exact row clock',()=>{
  assert.match(first,/listState\.layoutInfo\.visibleItemsInfo/);
  assert.match(first,/session\.turboFirstVisibleAtMs\.containsKey\(fingerprint\)/);
  assert.match(first,/val firstSeenAt = SystemClock\.elapsedRealtime\(\)/);
  assert.match(first,/session\.turboFirstVisibleAtMs\[fingerprint\] = firstSeenAt/);
  assert.match(first,/\}\.collect \{ visible ->/);
  assert.doesNotMatch(first,/\}\.collectLatest \{ visible ->/);
});
test('first five are privileged: only the current song, three fast bounded requests',()=>{
  assert.match(first,/val primaryEndsAt = firstSeenAt \+ TURBO_PRIMARY_VIDEO_MS/);
  assert.match(first,/suspend fun tryPrimaryUntil\(deadline: Long, maxAttempts: Int\)/);
  assert.match(first,/tryPrimaryUntil\(primaryEndsAt, maxAttempts = 3\)/);
  assert.match(first,/resolveVideoChunk\(listOf\(actual\)\)/);
  assert.match(first,/withTimeoutOrNull\(left\)/);
});
test('second five continue same cover video and do not begin replacement early',()=>{
  assert.match(first,/val graceEndsAt = primaryEndsAt \+ TURBO_PRIMARY_GRACE_MS/);
  assert.match(first,/tryPrimaryUntil\(graceEndsAt, maxAttempts = 2\)/);
  assert.match(first,/val waitForReplacement = graceEndsAt - SystemClock\.elapsedRealtime\(\)/);
  assert.ok(first.indexOf('tryPrimaryUntil(graceEndsAt')<first.indexOf('turboReplacementGate.withPermit'));
});
test('last five have absolute T+15 bound including semaphore queue and external lookup',()=>{
  assert.match(first,/val autoEndsAt = graceEndsAt \+ TURBO_REPLACEMENT_VIDEO_MS/);
  assert.match(first,/val budgetMs = autoEndsAt - SystemClock\.elapsedRealtime\(\)/);
  assert.match(first,/if \(budgetMs <= 0\) return@launch/);
  assert.match(first,/withTimeoutOrNull\(budgetMs\) \{/);
  assert.match(first,/turboReplacementGate.withPermit/);
  assert.match(first,/SystemClock\.elapsedRealtime\(\) >= autoEndsAt\) break/);
});
test('late first-song metadata never overwrites the replacement after 10 seconds',()=>{
  assert.match(primary,/firstSeenAt \+ TURBO_PRIMARY_VIDEO_MS \+ TURBO_PRIMARY_GRACE_MS/);
  assert.match(primary,/!isActive \|\| SystemClock\.elapsedRealtime\(\) >=/);
  assert.match(primary,/return@launch\s+\}\s+replaceSeed\(updated\)/);
});
test('replacement cannot overwrite a card if its network reply arrives after T+15',()=>{
  assert.match(first,/val verified = lab21VerifyAlternative\(spare\) \?: continue/);
  assert.match(first,/if \(!isActive \|\| SystemClock\.elapsedRealtime\(\) >= autoEndsAt\) break/);
  assert.ok(first.indexOf('SystemClock.elapsedRealtime() >= autoEndsAt) break')<first.indexOf('val change: (DiscogsVersionSeed)'));
});
test('no automatic search restarts on reentry, scroll or after T+15',()=>{
  assert.doesNotMatch(ui,/fun startTurboVideoBackground\(|val streamingVerifier = launch/);
  assert.match(first,/session\.turboAutoClosedFingerprints\.add\(fingerprint\)/);
  assert.match(first,/session\.turboFirstVisibleAtMs\.containsKey\(fingerprint\)/);
  assert.match(first,/val remainingUntilStop = autoEndsAt - SystemClock\.elapsedRealtime\(\)/);
});
test('music correctness: replacement is another same-work compatible cover not original slot',()=>{
  assert.match(first,/TitleMeaningResolver\.matchesBaseTitle\(/);
  assert.match(first,/modeAcceptsSeed\(spare\)/);
  assert.match(first,/DiscogsVersionSource\.recordingIdentityKey\(spare\) !in occupied/);
  assert.match(first,/isDistinctOriginalVersion\(spare\) == originalSide/);
  assert.match(ui,/CompilationTrackResolver\.isHardCompatible\(track, candidate\)/);
});
test('manual search gate opens only after fifteen seconds and never for video ready',()=>{
  assert.match(ui,/fun manualSwapReady\(seed: DiscogsVersionSeed\)/);
  assert.match(ui,/SystemClock\.elapsedRealtime\(\) - it >= TURBO_AUTO_TOTAL_MS/);
  assert.match(manual,/if \(hasPublishableVideo\(current\)\) return/);
  assert.match(manual,/SystemClock\.elapsedRealtime\(\) - firstSeen < TURBO_AUTO_TOTAL_MS/);
});
test('manual retries truly work: preload, existing videos then online new cover discovery',()=>{
  assert.match(manual,/adoptFrom\(session\.turboReserve \+ turboRankedPool\(\)\)/);
  assert.match(manual,/adoptFrom\(results\)/);
  assert.match(manual,/CoverDiscoverySources\.discover\(/);
  assert.match(manual,/withTimeoutOrNull\(12_000L\)/);
  assert.match(manual,/adoptFrom\(fresh\)/);
});
test('manual search excludes original recording, same-work validation and tracks attempts',()=>{
  assert.match(manual,/candidate\.fingerprint != current\.fingerprint/);
  assert.match(manual,/DiscogsVersionSource\.recordingIdentityKey\(candidate\) !in occupied/);
  assert.match(manual,/TitleMeaningResolver\.matchesBaseTitle\(anchor, candidate\.trackTitle\)/);
  assert.match(manual,/tried\.add\(candidate\.fingerprint\)/);
  assert.match(manual,/lab21VerifyAlternative\(candidate\)/);
});
test('manual searches are one per tap with no endless retry loop or background schedule',()=>{
  assert.match(manual,/if \(manualVideoSearchJob\?\.isActive == true\)/);
  assert.match(manual,/manualVideoSearchJob = scope\.launch \{/);
  assert.match(manual,/resolvingFingerprint = current\.fingerprint/);
  assert.doesNotMatch(manual,/while \(isActive/);
  assert.doesNotMatch(manual,/delay\(/);
});
test('manual success changes poster and YouTube link together in ONE slot',()=>{
  assert.match(manual,/val thumbnail = "https:\/\/i\.ytimg\.com\/vi\/\$videoId\/hqdefault\.jpg"/);
  assert.match(manual,/val winner = verified\.copy\(/);
  assert.match(manual,/publishedOriginalSnapshots = publishedOriginalSnapshots\.map\(change\)/);
  assert.match(manual,/publishedCoverSnapshots = publishedCoverSnapshots\.map\(change\)/);
  assert.match(manual,/results = results\.map\(change\)/);
  assert.match(manual,/session\.stableOrder = session\.stableOrder\.map/);
});
test('manual replacement forces video-specific thumbnail over old editorial cache',()=>{
  assert.match(ui,/"MusicLab LAB22 video poster" in seed\.sourceNames/);
  assert.match(ui,/sourceNames = \(verified\.sourceNames \+\s+"MusicLab LAB22 video poster"\)\.distinct\(\)/);
  assert.match(manual,/if \(thumbnail == current\.coverUrl\) continue/);
});
test('no manual search button once video is verified; tap starts playback',()=>{
  assert.match(ui,/videoReadyVerified = hasPublishableVideo\(seed\)/);
  assert.match(ui,/automaticVideoManaged = isTurbo/);
  assert.match(ui,/manualSwitchAvailable = isTurbo && manualSwapReady\(seed\)/);
  assert.match(ui,/!videoReadyVerified &&\s+\(if \(automaticVideoManaged\) manualSwitchAvailable/);
  assert.match(ui,/\.clickable\(onClick = onPlay\)/);
});
test('network and ranking never rewrite audio stream, buffer and player',()=>{
  assert.doesNotMatch(first,/InnerTubeXPlayer\.playerResponseForPlayback|prepareAudioStream/);
  assert.doesNotMatch(manual,/InnerTubeXPlayer\.playerResponseForPlayback/);
});
test('archived original cover handoff and poster/video cache reused',()=>{
  assert.match(archive,/CloudMusicDiscovery\.rememberArchiveSelection\(item, endpoint\)/);
  assert.match(ui,/val earlyArchiveSeeds = archiveSeeds\(listOfNotNull\(selectedArchiveItem\)\)/);
  assert.match(ui,/playbackVideoId = item\.playbackVideoId/);
});
test('APK stays update-in-place with same Turbo signing and versionCode 1022',()=>{
  assert.match(stamp,/TURBO_LAB22/);
  assert.match(stamp,/MusicLab-Turbo-LAB22/);
  assert.match(stamp,/1022/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.doesNotMatch(stamp,/keytool|newkey/);
});
