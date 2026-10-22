import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const code = p => fs.readFileSync(p,'utf8');
const direct=code('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const shs=code('app/src/main/kotlin/com/metrolist/music/ui/component/SecondHandSongsCoverSource.kt');
const gate=code('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');
const card=direct.split('private fun DiscogsVersionCard(')[1];
const manual=direct.split('fun manualSwapReady(seed: DiscogsVersionSeed): Boolean')[1].split('fun play(seed: DiscogsVersionSeed)')[0];

test('LAB44 any missing-video red-dot card shows manual action immediately',()=>{
  assert.match(manual,/isTurbo && mode == DiscogsDirectMode.COVER/);
  assert.doesNotMatch(manual,/seed\.fingerprint in session\.turboAutoClosedFingerprints/);
  assert.doesNotMatch(manual,/15 secondi|TURBO_AUTO_TOTAL_MS/);
  assert.match(card,/if \(modernCoverCard \|\| manualSwitchAvailable \|\| !videoReadyVerified\)/);
  assert.match(card,/Text\("Cerca video"\)/); assert.match(card,/Text\("Cambia cover"\)/);
});

test('LAB44 eliminate false blocking toast from manual branch',()=>{
  assert.doesNotMatch(manual,/Ricerca automatica 5\+5\+5 ancora attiva/);
  assert.doesNotMatch(manual,/current\.fingerprint !in session\.turboAutoClosedFingerprints/);
  assert.match(manual,/if \(manualVideoSearchJob\?\.isActive == true\)/);
});

test('LAB44 manual priority preempts auto-video without affecting music service',()=>{
  assert.match(manual,/turboAutoVideoJob\?\.cancel\(\)/);
  assert.match(manual,/turboAutoVideoJob = null/);
  assert.match(manual,/manualVideoSearchJob = scope\.launch/);
  assert.doesNotMatch(manual,/pauseCoverBackgroundForPlayback\(\)/);
  assert.doesNotMatch(manual,/setCoverPerformanceLoad\(/);
});

test('LAB44 manual search resumes bounded coordinator when allowed',()=>{
  assert.match(manual,/session\.turboAutoStartedAtMs \+ TURBO_VIDEO_COVERAGE_MAX_MS/);
  assert.match(manual,/!playbackIsCritical\(\)/);
  assert.match(manual,/turboSearchGeneration\+\+/);
});

test('LAB44 manual 8s window includes longer verified source discovery',()=>{
  assert.match(manual,/withTimeoutOrNull\(8_000L\)/);
  assert.match(manual,/withTimeoutOrNull\(4_000L\)/);
  assert.match(manual,/forceRefresh = true/);
});

test('LAB44 user-driven switch requires verified separate singer/recording/video',()=>{
  assert.match(manual,/modeAcceptsSeed\(candidate\)/);
  assert.match(manual,/isDistinctOriginalVersion\(candidate\) == chosenSide/);
  assert.match(direct,/CompilationTrackResolver\.isHardCompatible\(track, candidate\)/);
  assert.match(manual,/hasPublishableVideo\(verified\)/);
  assert.match(manual,/videoId in usedIds/);
});

test('LAB44 SHS work parser recognizes structured versions/performances/covers',()=>{
  assert.match(shs,/listOf\("versions", "performances", "covers"\)/);
  assert.match(shs,/uri\.contains\("\/performance\/"\)/);
  assert.match(shs,/MAX_WORK_VERSION_DETAILS = 8/);
  assert.match(shs,/TitleMeaningResolver\.sameArtist\(version\.artist, originalArtist\)/);
});

test('LAB44 SHS API remains bounded and never attempts captcha bypass',()=>{
  assert.match(shs,/REQUEST_TIMEOUT_MS/);
  assert.match(shs,/SecondHandSongsStatus\.RATE_LIMITED/);
  assert.match(shs,/header\("Accept", "application\/json"\)/);
  assert.doesNotMatch(shs,/bypassCaptcha|solveCaptcha|undetectedChrome/);
});

test('LAB44 same-work and user identity filters remain unchanged',()=>{
  assert.match(gate,/hasDocumentaryRelation/);
  assert.match(gate,/capUnverifiedScore/);
  assert.match(direct,/CoverSameWorkGate\.hasWorkIdentity\(/);
  assert.match(direct,/TURBO_RESULT_QUOTA = 50/);
});

test('LAB44 red dot color and approved card dimensions stay identical',()=>{
  assert.match(card,/color = if \(videoReadyVerified\) Color\(0xFF22C871\)/);
  assert.match(card,/else Color\(0xFFFA5757\)/);
  assert.match(card,/Modifier\.size\(126\.dp\)/);
  assert.match(card,/modernCoverCard/);
});
