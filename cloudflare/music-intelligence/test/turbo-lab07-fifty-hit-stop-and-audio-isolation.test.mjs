import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const interval=(a,z)=>{const i=b.indexOf(a),j=b.indexOf(z,i+1);assert.ok(i>=0&&j>i,'Missing code interval');return b.slice(i,j);};
const turbo=interval('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const quota=interval('fun stopAtFullQuota(): Boolean {','val streamingVerifier = launch');
const verify=interval('val streamingVerifier = launch','val archiveDeferred = async(Dispatchers.IO)');
const warm=interval('// Thumbnail warming never extends the burst','streamingVerifier.cancel()');

test('QUOTA 01 native maximum is fifty, not fifty per source',()=>{
 assert.match(b,/TURBO_RESULT_QUOTA = 50/);
 assert.match(quota,/coverCandidatePool\(\)\.count\(::hasPublishableVideo\) < TURBO_RESULT_QUOTA/);
});
test('QUOTA 02 first fifty verified recordings immediately activate early shutdown',()=>{
 assert.match(quota,/quotaReached = true/);
 assert.match(quota,/providerJobs\.forEach \{ it\.cancel\(\) \}/);
});
test('QUOTA 03 the shutdown cancels producer child jobs, never parent search job',()=>{
 assert.match(turbo,/val providerJobs = mutableListOf<Job>\(\)/);
 assert.equal(quota.includes('searchJob?.cancel()'),false);
 assert.equal(quota.includes('coroutineContext.cancel()'),false);
});
test('QUOTA 04 every independent source runner participates in early cancellation',()=>{
 assert.match(turbo,/listOf\(archiveDeferred, memoryDeferred, firstPageDeferred, externalDeferred\)/);
 assert.match(turbo,/if \(quotaReached\) providerJobs\.forEach \{ it\.cancel\(\) \}/);
});
test('QUOTA 05 verifier stops before waiting for another 110ms callback after fifty are ready',()=>{
 assert.match(verify,/if \(stopAtFullQuota\(\)\) break/);
 assert.match(verify,/val early = withTimeoutOrNull\(110L\)/);
 assert.ok(verify.indexOf('if (stopAtFullQuota()) break')<verify.indexOf('val early ='));
});
test('QUOTA 06 verification checks quota immediately after each bounded chunk',()=>{
 assert.match(verify,/withTimeoutOrNull\(minOf\(remaining, 1_250L\)\) \{\s*resolveVideoChunk\(pending\)\s*\}\s*if \(stopAtFullQuota\(\)\) break/);
});
test('QUOTA 07 canceled deferred producers are caught only when quota was reached',()=>{
 const count=(turbo.match(/if \(!quotaReached\) throw cancel/g)||[]).length;
 assert.equal(count,4);
 assert.match(turbo,/catch \(cancel: CancellationException\)/);
});
test('QUOTA 08 no image preloading delays full quota longer than four hundred milliseconds',()=>{
 assert.match(warm,/val imageBudget = minOf\(\s*TURBO_THUMBNAIL_RESERVE_MS,/);
 assert.match(b,/TURBO_THUMBNAIL_RESERVE_MS = 400L/);
 assert.match(warm,/thumbnails\.take\(12\)/);
});
test('QUOTA 09 active audio disables image prewarming and retains three image lanes when idle',()=>{
 assert.match(warm,/!playbackIsNormallyPlaying\(\) && !playbackIsCritical\(\)/);
 assert.match(warm,/val gate = Semaphore\(3\)/);
});
test('QUOTA 10 the final snapshot still publishes once, closes queues, releases music governor',()=>{
 assert.match(turbo,/publishedCoverSnapshots = finalRanked/);
 assert.match(turbo,/streamingCandidates\.cancel\(\)/);
 assert.match(turbo,/supplementalSeeds\.cancel\(\)/);
 assert.match(turbo,/active = false, heavy = false/);
 assert.equal(player.byteLength>0,true);
});
