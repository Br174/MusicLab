import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const src=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const nav=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const section=(start,end)=>{
 const a=b.indexOf(start),z=b.indexOf(end,a+start.length);
 assert.ok(a>=0&&z>a,'Missing '+start+' -> '+end);
 return b.slice(a,z);
};
const publisher=section('suspend fun publishReadyBatches()','suspend fun resolveNextVideoBatch(');
const turbo=section('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const sourceCallbacks=section('onEarlyVideoCandidates = { quick ->','// Producers already launched together');
const effects=section('LaunchedEffect(sessionKey, rankingFrozen, originalSectionFrozen)', '// LAB68: ignore unrelated player/animation recompositions');
test('AUDIT 01 exact duplicate provider records enter one concurrent set, not unlimited repeat network work',()=>{
 assert.match(turbo,/ConcurrentHashMap\.newKeySet<CoverSourceCandidate>\(\)/);
 assert.match(sourceCallbacks,/val fresh = quick\.filter \{ sourceEvidence\.add\(it\) \}/);
 assert.match(sourceCallbacks,/val fresh = items\.filter \{ sourceEvidence\.add\(it\) \}/);
 assert.equal(b.includes('sourceEvidence.addAll(quick)'),false);
 assert.equal(b.includes('sourceEvidence.addAll(items)'),false);
});
test('AUDIT 02 callbacks only awaken verification for genuinely new documentary evidence',()=>{
 const count=(sourceCallbacks.match(/if \(fresh\.isNotEmpty\(\)\) streamingCandidates\.trySend\(fresh\)/g)||[]).length;
 assert.equal(count,2);
 assert.match(turbo,/external\?\.candidates\.orEmpty\(\)\)\.distinct\(\)\.map \{ source ->/);
});
test('AUDIT 03 different catalog sources retain their separate evidence and video identity',()=>{
 assert.match(src,/internal data class CoverSourceCandidate\(/);
 assert.match(src,/val sources: List<String>/);
 assert.match(src,/val playbackVideoId: String\? = null/);
 assert.match(b,/DiscogsVersionSource\.mergeCrossSourceEvidence/);
});
test('AUDIT 04 old five-row publisher cannot run at all inside Turbo Cover',()=>{
 assert.match(publisher,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return/);
 assert.match(publisher,/val slots = minOf\(DIRECT_VIDEO_BATCH_SIZE/);
 assert.equal(turbo.includes('publishReadyBatches()'),false);
});
test('AUDIT 05 old 7.6-second / 2.8-second UI publisher effects exit before scheduling Turbo timers',()=>{
 assert.match(effects,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen\) \{\s*if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return@LaunchedEffect/);
 assert.match(effects,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen, publishedCoverSnapshots\.size, visibleLimit\) \{\s*if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return@LaunchedEffect/);
});
test('AUDIT 06 a Turbo session publishes one frozen snapshot of up to 50 metadata-verified covers',()=>{
 assert.match(turbo,/val finalRanked = coverCandidatePool\(\)\s*\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
 assert.equal((turbo.match(/publishedCoverSnapshots = finalRanked/g)||[]).length,2); // UI and session, same snapshot
 assert.match(turbo,/visibleLimit = TURBO_RESULT_QUOTA/);
 assert.match(b,/LazyColumn\(/);
});
test('AUDIT 07 all concurrent runners have one five-second outer wall-clock budget',()=>{
 assert.match(b,/TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(turbo,/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
 for(const k of ['archiveDeferred','memoryDeferred','firstPageDeferred','externalDeferred']){
  assert.ok(turbo.includes('val '+k+' = async'),'missing '+k);
 }
});
test('AUDIT 08 if 50 videos arrive early, all source runners stop but final snapshot still publishes',()=>{
 assert.match(turbo,/val providerJobs = mutableListOf<Job>\(\)/);
 assert.match(turbo,/quotaReached = true/);
 assert.match(turbo,/providerJobs\.forEach \{ it\.cancel\(\) \}/);
 assert.match(turbo,/if \(quotaReached\) providerJobs\.forEach \{ it\.cancel\(\) \}/);
 assert.match(turbo,/if \(!quotaReached\) throw cancel/);
});
test('AUDIT 09 after seal memory work, provider IO and performance governor stop, no scroll network rediscovery',()=>{
 for(const needle of ['streamingCandidates.cancel()','supplementalSeeds.cancel()',
 'videoPreloadJob?.cancel()','verificationJob?.cancel()',
 'backgroundResumeJob?.cancel()','CloudMusicDiscovery.cancelArchiveRequests()']){
  assert.ok(turbo.includes(needle),'missing '+needle);
 }
 assert.match(turbo,/setCoverPerformanceLoad\(\s*active = false, heavy = false/);
 assert.match(b,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return\s*\n/);
});
test('AUDIT 10 audio player remains byte-identical, Home direct navigation and 50-item metadata buffer is bounded',()=>{
 const blob=Buffer.concat([Buffer.from('blob '+player.length+'\0'),player]);
 assert.equal(createHash('sha1').update(blob).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
 assert.match(b,/DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 64/);
 assert.match(turbo,/!playbackIsNormallyPlaying\(\) && !playbackIsCritical\(\)/);
 assert.equal((nav.match(/if \(!isSearchItem\) \{/g)||[]).length,2);
});
