import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const ui = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const original = ui.split('fun consensusOriginalArtist(candidates: List<CoverSourceCandidate>)')[1].split('fun mergeDiscoveryOutcomes(')[0];
const automatic = ui.split('LaunchedEffect(sessionKey, turboSearchGeneration) {')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const play = ui.split('fun pauseCoverBackgroundForPlayback()')[1].split('fun saveDecision(')[0];
const selected = ui.split('playbackLaunchJob = scope.launch {')[1].split('fun swipeCover(')[0];
const discovery = ui.split('val turboOriginalUncertain =')[1].split('// LAB58:')[0];
const resolution = ui.split('suspend fun resolveVideoChunk(')[1].split('// LAB08 Pollicino')[0];

test('LAB24 uses the existing coordinator, no parallel automatic search engine', () => {
  assert.equal(ui.match(/LaunchedEffect\(sessionKey, turboSearchGeneration\)/g)?.length, 1);
  assert.match(automatic, /turboAutoVideoJob = currentCoroutineContext\(\)\[Job\]/);
  assert.match(play, /turboAutoVideoJob\?\.cancel\(\)/);
  assert.doesNotMatch(ui, /fun startTurboVideoBackground\(/);
});

test('playback priority cancels background queries and never restarts a fresh clock', () => {
  assert.match(play, /backgroundPausedForPlayback = true/);
  assert.match(play, /turboAutoVideoJob\?\.cancel\(\)/);
  assert.match(play, /SystemClock\.elapsedRealtime\(\) < maxOf\(/);
  assert.match(play, /turboSearchGeneration\+\+/);
  assert.match(automatic, /val globalStart = session\.turboPosterPublishedAtMs\.values\.minOrNull\(\)/);
});

test('automatic queues pause on playback start, buffering and audio risk', () => {
  assert.match(automatic, /backgroundWorkBlocked\(\)/);
  assert.match(automatic, /isCoverAudioAtRisk\(\)/);
  assert.match(automatic, /playbackIsNormallyPlaying\(\) && lane != 0/);
  assert.match(resolution, /!playbackPriority/);
  assert.match(resolution, /Player\.STATE_BUFFERING/);
});

test('explicit user tap can resolve its own recording despite automatic timeouts', () => {
  assert.match(selected, /resolveVideoChunk\(listOf\(currentSeed\), playbackPriority = true\)/);
  assert.match(resolution, /&& !playbackPriority/);
  assert.match(ui, /beginPlaybackPriorityBurst/);
  assert.match(ui, /pauseCoverBackgroundForPlayback\(\)/);
});

test('all 50 candidates have a fair initial lane including the final 25', () => {
  assert.match(automatic, /rows\.take\(10\)/);
  assert.match(automatic, /rows\.drop\(10\)\.take\(15\)/);
  assert.match(automatic, /rows\.drop\(25\)\.take\(13\)/);
  assert.match(automatic, /rows\.drop\(38\)/);
  const rows = Array.from({length: 50}, (_,i)=>i);
  const partitions=[rows.slice(0,10),rows.slice(10,25),rows.slice(25,38),rows.slice(38)];
  assert.deepEqual(partitions.map(p=>p.length), [10,15,13,12]);
  assert.deepEqual([...new Set(partitions.flat())],rows);
});

test('each automatic video attempt has a bounded sub-deadline', () => {
  assert.match(automatic,/remaining\.coerceAtMost\(2_200L\)/);
  assert.match(automatic,/SystemClock\.elapsedRealtime\(\) < currentVideoEndMs\(\)/);
  assert.match(automatic,/turboReplacementGate\.withPermit/);
  assert.match(automatic,/TURBO_RESULT_QUOTA/);
});

test('original artist requires documentary attribution, never frequency alone', () => {
  assert.match(original,/it\.originalWorkReference/);
  assert.doesNotMatch(original,/evidenceScore >= 14|enoughConsensus|winner\.value\.size >= 3/);
  assert.match(original,/ranked\.getOrNull\(1\)\?\.value\?\.size == winner\.value\.size/);
});

test('when cover is the input, reuse bounded documentary recovery without presuming artist', () => {
  assert.match(discovery,/isTurbo && mode == DiscogsDirectMode\.COVER/);
  assert.match(discovery,/!hasExplicitOriginalEvidence/);
  assert.match(discovery,/withTimeoutOrNull\(1_500L\)/);
  assert.match(discovery,/originalArtist = ""/);
  assert.match(discovery,/CoverDiscoverySources\.discover\(/);
  assert.match(discovery,/mergeDiscoveryOutcomes\(/);
});

test('do not change ranking quota, source ingestion, graphics or native player',()=>{
  assert.match(ui,/private const val TURBO_RESULT_QUOTA = 50/);
  assert.match(ui,/sealPostersOnce\(\)/);
  assert.match(ui,/sealSecondPostersOnce\(\)/);
  assert.match(ui,/TURBO_PRIMARY_VIDEO_MS = 5_000L/);
  assert.match(ui,/TURBO_PRIMARY_GRACE_MS = 5_000L/);
  assert.match(ui,/TURBO_REPLACEMENT_VIDEO_MS = 5_000L/);
});

test('LAB24 update identity uses exactly the existing signing family',()=>{
  const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab24-env.py','utf8');
  assert.match(stamp,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE="1024"/);
  assert.match(stamp,/MusicLab-Turbo-LAB24/);
  assert.doesNotMatch(stamp,/keytool|newkey/);
});

test('global playback recovers transient expired URLs outside Cover', () => {
  const service=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
  const retry=service.split('private fun refreshStreamAndRetry(')[1].split('private fun handleFileNotFoundError(')[0];
  assert.match(retry,/val positionDrift = positionNow - retryPosition/);
  assert.match(retry,/positionDrift > 2_000L \|\| positionDrift < -2_000L/);
  assert.match(retry,/player\.playbackState == Player\.STATE_READY/);
  assert.match(retry,/player\.currentMediaItem\?\.mediaId != mediaId/);
  assert.match(retry,/player\.playWhenReady != retryPlayWhenReady/);
  assert.doesNotMatch(retry,/player\.currentPosition != retryPosition/);
  assert.match(service,/MusicLabLAB24Audio/);
  assert.match(service,/fatal_source_error=/);
});

test('LAB24 Android labels must never reuse LAB23 after runner stamping',()=>{
  const stamp = fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab24-env.py','utf8');
  const workflow = fs.readFileSync('.github/workflows/turbo-lab24-audio-50-original-apk.yml','utf8');
  assert.match(stamp, /'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 19"': 'UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 24"'/);
  assert.match(stamp, /'export METROLIST_APP_NAME="MusicLab Turbo LAB 19"': 'export METROLIST_APP_NAME="MusicLab Turbo LAB 24"'/);
  assert.doesNotMatch(stamp, /Turbo LAB 23/);
  assert.match(workflow, /UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 24"/);
  assert.match(workflow, /export METROLIST_APP_NAME="MusicLab Turbo LAB 24"/);
  assert.match(workflow, /application-label:'MusicLab Turbo LAB 24'/);
  assert.doesNotMatch(workflow, /Turbo LAB 23/);
});
