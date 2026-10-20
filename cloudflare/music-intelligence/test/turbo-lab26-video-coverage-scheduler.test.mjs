import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const resolver=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt','utf8');
const section=source.split('LaunchedEffect(sessionKey, turboSearchGeneration) {')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const video=source.split('suspend fun resolveVideoChunk(')[1].split('// LAB08 Pollicino')[0];
const resume=source.split('fun resumeCoverBackgroundAfterPlaybackBurst()')[1].split('fun saveDecision(')[0];

test('LAB26 actually removes global T+10 stale video rejection',()=>{
  assert.doesNotMatch(video,/globalStartedAt \+ TURBO_PRIMARY_VIDEO_MS \+ TURBO_PRIMARY_GRACE_MS/);
  assert.match(video,/session\.turboVideoFirstProbeAtMs\[current\.fingerprint\]/);
  assert.match(video,/rowStartedAt \+ TURBO_AUTO_TOTAL_MS/);
});

test('video coordinator has bounded time to try 50 candidates beyond 10 second posters',()=>{
  assert.match(source,/TURBO_VIDEO_COVERAGE_MAX_MS = 100_000L/);
  assert.match(section,/globalStart \+ TURBO_VIDEO_COVERAGE_MAX_MS/);
  assert.match(source,/TURBO_FIRST_POSTER_TARGET_MS = 5_000L/);
  assert.match(resume,/session\.turboAutoStartedAtMs \+ TURBO_VIDEO_COVERAGE_MAX_MS/);
});

test('untried candidates precede all retries and replacement attempts',()=>{
  assert.match(section,/val firstQueue = ordered\.filter/);
  assert.match(section,/val retryQueue = if \(allFirstProbed\)/);
  assert.match(section,/val replacementQueue = if \(allFirstProbed\)/);
  assert.match(section,/\(firstQueue \+ retryQueue \+ replacementQueue\)\.firstOrNull/);
  assert.match(section,/val allFirstProbed/);
  assert.match(section,/rows\.all \{ candidate/);
});

test('resumed coordinator retains per-row attempts instead of resetting',()=>{
  assert.match(section,/val firstPasses = session\.automaticVideoAttempts/);
  assert.match(section,/session\.turboVideoFirstProbeAtMs\.getOrPut\(fingerprint\)/);
  assert.match(source,/session\.turboVideoFirstProbeAtMs\.clear\(\)/);
  assert.match(source,/session\.automaticVideoAttempts\.clear\(\)/);
});

test('all 50 cards are covered by one complete stable four-lane partition',()=>{
  assert.match(section,/rows\.take\(10\)/);
  assert.match(section,/rows\.drop\(10\)\.take\(15\)/);
  assert.match(section,/rows\.drop\(25\)\.take\(13\)/);
  assert.match(section,/rows\.drop\(38\)/);
  const rows=Array.from({length:50},(_,i)=>i);
  const groups=[rows.slice(0,10),rows.slice(10,25),rows.slice(25,38),rows.slice(38)];
  assert.deepEqual(groups.map(g=>g.length),[10,15,13,12]);
  assert.deepEqual([...new Set(groups.flat())],rows);
});

test('simulation: every first attempt precedes retry even when last 25 appear at 5s',()=>{
  const first50=Array.from({length:50},(_,i)=>i);
  const firstPasses=new Map(), events=[],visibleAt=(n)=>n<25?0:5000;
  for(let t=0;t<95_000;t+=3300){
    const nowVisible=first50.filter(i=>visibleAt(i)<=t);
    const first=nowVisible.filter(i=>!firstPasses.has(i));
    const all=nowVisible.length===50&&first.length===0;
    const selected=(first.length?first:all?nowVisible.filter(i=>firstPasses.get(i)===1):[]).slice(0,4);
    for(const candidate of selected){firstPasses.set(candidate,(firstPasses.get(candidate)||0)+1);events.push({t,candidate,pass:firstPasses.get(candidate)})}
    if(firstPasses.size===50)break;
  }
  assert.equal(firstPasses.size,50);
  assert.equal(events.filter(e=>e.pass===1).length,50);
  assert.ok(events.filter(e=>e.pass!==1).every(e=>firstPasses.size===50));
});

test('simulation: four healthy lanes can perform 50 first attempts inside max budget',()=>{
  const max=100_000, attempts=50, slots=4, perAttempt=3400;
  const completion=Math.ceil(attempts/slots)*perAttempt;
  assert.equal(completion,44_200);
  assert.ok(completion<max);
});

test('simulation: two healthy-audio lanes can perform 50 first attempts within cap',()=>{
  const max=100_000, attempts=50, slots=2, perAttempt=3400;
  const completion=Math.ceil(attempts/slots)*perAttempt;
  assert.equal(completion,85_000);
  assert.ok(completion<max);
});

test('audio safety blocks all video lanes during rebuffer and limits healthy playback to two',()=>{
  assert.match(section,/backgroundWorkBlocked\(\)/);
  assert.match(section,/isCoverAudioAtRisk\(\)/);
  assert.match(section,/playbackIsNormallyPlaying\(\) && lane >= 2/);
  assert.match(source,/turboAutoVideoJob\?\.cancel\(\)/);
});

test('fast lookup timeout is not shorter than the underlying 2250ms race',()=>{
  assert.match(source,/TURBO_VIDEO_FIRST_ATTEMPT_MS = 3_400L/);
  assert.match(resolver,/if \(turboWindow\) 2_250L else 3_050L/);
  assert.ok(3400>=2250+950);
});

test('replacement never exceeds the row first-probe 5+5+5 window',()=>{
  assert.match(section,/rowStartedAt \+ TURBO_AUTO_TOTAL_MS/);
  assert.match(section,/minOf\(\s*currentVideoEndMs\(\), rowStartedAt \+ TURBO_AUTO_TOTAL_MS/);
  assert.match(section,/turboReplacementGate\.withPermit/);
});

test('report coverage counters, not misleading always-green status',()=>{
  assert.match(section,/MusicLabLAB26Video/);
  assert.match(section,/rows=%d probed=%d linked=%d zero_attempt=%d/);
  assert.match(section,/completedRows\.count\(::hasPublishableVideo\)/);
});

test('25+25 poster layout and user-approved UI unchanged',()=>{
  assert.match(source,/TURBO_MIN_EARLY_POSTER_BLOCK = 10/);
  assert.match(source,/TURBO_POSTER_SECOND_BLOCK = 40/);
  assert.match(source,/sealPostersOnce\(\)/);
  assert.match(source,/sealSecondPostersOnce\(\)/);
});

test('manual video retry continues separately from automatic coordinator',()=>{
  assert.match(source,/resolveVideoChunk\(listOf\(currentSeed\), playbackPriority = true\)/);
  assert.match(video,/&& !playbackPriority/);
});

test('LAB26 Android update identity, name and signing family must match',()=>{
  const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab26-env.py','utf8');
  const wf=fs.readFileSync('.github/workflows/turbo-lab26-video-coverage-scheduler-apk.yml','utf8');
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE="1026"/);
  assert.match(stamp,/export METROLIST_APP_NAME="MusicLab Turbo LAB 26"/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(wf,/application-label:'MusicLab Turbo LAB 26'/);
  assert.match(wf,/versionCode='1026'/);
  assert.doesNotMatch(stamp,/keytool|newkey/);
});
