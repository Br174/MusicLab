import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const app = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const env = fs.readFileSync('uab-project.env','utf8');
const player = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt','utf8');

test('LAB11: the Turbo ten-second clock starts on search, not after providers', () => {
  assert.match(app,/TURBO_BURST_DEADLINE_MS = 10_000L/);
  assert.match(app,/session\.turboBurstStartedAtMs = if \(isTurbo && mode == DiscogsDirectMode\.COVER\) turboCommandStartedAt/);
  assert.match(app,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
});
test('LAB11: a single early poster block; maximum 50, original ranking order', () => {
  assert.match(app,/TURBO_RESULT_QUOTA = 50/);
  assert.match(app,/TURBO_FIRST_POSTER_DEADLINE_MS = 1_800L/);
  assert.match(app,/fun sealPostersOnce\(\)/);
  assert.match(app,/coverCandidatePool\(\)\.filter\(::modeAcceptsSeed\)/);
  assert.match(app,/DirectVersionSort\.RELEVANCE/);
  assert.match(app,/publishedCoverSnapshots = ranked/);
  assert.match(app,/if \(publishedCoverSnapshots\.isEmpty\(\)\) \{/);
  assert.match(app,/providerJobs\.forEach \{ it\.cancel\(\) \}/);
});
test('LAB11: audio check is independent from video metadata, bounded and truthful', () => {
  assert.match(app,/TURBO_STREAM_PROBE_MS = 850L/);
  assert.match(app,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(app,/session\.turboStreamReadyIds\.add\(readyId\)/);
  assert.match(app,/videoReadyVerified -> "Video trovato · audio non confermato"/);
  assert.match(app,/audioReadyVerified -> "Flusso audio trovato"/);
  assert.match(app,/Flussi audio trovati:/);
});
test('LAB11: no delayed automatic searches or twenty-second window after burst', () => {
  assert.doesNotMatch(app,/startTurboAutomaticVideoLookup/);
  assert.doesNotMatch(app,/TURBO_AUTO_VIDEO_PRIORITY_WINDOW_MS/);
  assert.match(app,/session\.turboFinished = true/);
  assert.match(app,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
  assert.match(app,/videoPreloadJob\?\.cancel\(\)/);
});
test('LAB11: native player API stays in its separate original implementation', () => {
  assert.match(player,/object InnerTubeXPlayer/);
  assert.match(player,/bundle\(\)\.extractor\.extract\(/);
  assert.match(app,/connection\.playNow\(selectedItem\)/);
  assert.match(app,/if \(mode == DiscogsDirectMode\.COVER/);
});
test('LAB11: Android app keeps Turbo family and identity, updates to 1011', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1011"/);
  assert.match(env,/UAB_OUTPUT_NAME="MusicLab-Turbo-LAB11"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-turbo-family-02-test-reuses-lab-test-key"/);
  assert.match(env,/export MUSICLAB_VERSION_CODE="1011"/);
});
