import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const code = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');
const meld = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
function block(start, end) {
  const i=code.indexOf(start), j=code.indexOf(end,i+start.length);
  assert.ok(i>=0 && j>i, 'missing source boundary '+start);
  return code.slice(i,j);
}

test('LAB65B raw COVER.INFO/YouTube IDs enter metadata verification even if thumbnail exists',()=>{
  const batch=block('suspend fun resolveNextVideoBatch(', '// LAB61: intentionally no media-metadata prewarming.');
  assert.match(batch,/!hasPublishableVideo\(seed\)/);
  assert.doesNotMatch(batch,/!isPlayReady\(seed\)/);
  const resolver=block('suspend fun resolveVideoChunk(', 'fun videoPriorityPool(');
  assert.match(resolver,/if \(existingId\.isNotBlank\(\)\)/);
  assert.match(resolver,/YouTube\.queue\(videoIds = listOf\(existingId\)\)/);
  assert.match(resolver,/CompilationTrackResolver\.isHardCompatible/);
  assert.match(resolver,/session\.playReadyVideoIds \+= candidate\.id/);
  assert.match(code,/fun needsAutomaticVideo\(seed: DiscogsVersionSeed\): Boolean =\s*!hasPublishableVideo\(seed\)/);
});

test('LAB65B readiness is independently checked, never inferred from raw source ID',()=>{
  assert.match(code,/fun preparationReadyVideoCount\(\): Int =[\s\S]{0,180}results\.count\(::hasPublishableVideo\)/);
  const worker=block('fun scheduleVideoPreload()', 'fun pauseCoverBackgroundForPlayback()');
  assert.doesNotMatch(worker,/workWindow\.all\(::isPlayReady\)/);
  assert.match(worker,/!hasPublishableVideo\(seed\)/);
  assert.match(worker,/if \(workWindow\.isEmpty\(\)\) \{\s*publishReadyBatches\(\)/);
  assert.match(worker,/mode != DiscogsDirectMode\.COVER && playbackIsNormallyPlaying\(\)/);
});

test('LAB65B metadata verification continues at most one recording at a time while another song plays',()=>{
  const worker=block('fun scheduleVideoPreload()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(worker,/if \(backgroundWorkBlocked\(\)/);
  assert.match(worker,/if \(playbackIsNormallyPlaying\(\)\) \{\s*DIRECT_COVER_PLAYBACK_BATCH_SIZE/);
  assert.match(code,/DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1/);
  assert.match(worker,/heavy = !playbackIsNormallyPlaying\(\)/);
  assert.match(worker,/delay\(if \(playbackIsNormallyPlaying\(\)\) 150 else 40\)/);
  const resume=block('fun resumeCoverBackgroundAfterPlaybackBurst()', 'fun saveDecision(');
  assert.match(resume,/scheduleVideoPreload\(\)/);
  assert.doesNotMatch(resume,/if \(!playbackIsNormallyPlaying\(\)\) \{\s*scheduleVideoPreload\(\)/);
});

test('LAB65B original gate cannot deadlock the entire Cover list',()=>{
  assert.match(code,/DIRECT_ORIGINAL_FIRST_GATE_MS = 7_600L/);
  const effect=block('// LAB65B: release the original-first gate', 'LaunchedEffect(visibleLimit, sourceDiagnostics');
  assert.match(effect,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen\)/);
  assert.match(effect,/delay\(remaining\)/);
  assert.match(effect,/publishReadyBatches\(\)/);
  assert.match(effect,/scheduleVideoPreload\(\)/);
  const publish=block('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(publish,/publishedOriginalSnapshots = candidates\.filter\(::hasPublishableVideo\)/);
  assert.match(publish,/val ready = settledPrefix\.filter\(::hasPublishableVideo\)/);
});

test('LAB65B retains native Meld and signed Family 01 update identity',()=>{
  const hash=createHash('sha1').update(Buffer.concat([Buffer.from('blob '+meld.length+'\0'),meld])).digest('hex');
  assert.equal(hash,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1003"/);
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-turbo-family-02-test-reuses-lab-test-key"/);
});