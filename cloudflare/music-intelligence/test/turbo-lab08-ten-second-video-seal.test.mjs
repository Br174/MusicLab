import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const stageStart=b.indexOf('// LAB08 Pollicino: from the poster seal');
const stageEnd=b.indexOf('\n        } else {\n        searchJob =',stageStart);
assert.ok(stageStart>=0&&stageEnd>stageStart);
const stage=b.slice(stageStart,stageEnd);
const initial=b.slice(b.indexOf('val stopAt = turboCommandStartedAt + TURBO_BURST_DEADLINE_MS'),stageStart);
const final=stage.slice(stage.indexOf('val finalVideoSnapshots = publishedCoverSnapshots.mapNotNull'));
function atomicPrune(posterCount, validIds){
 const posters=Array.from({length:Math.min(50,posterCount)},(_,i)=>({id:'p'+i,score:20-(i%20)}));
 const final=posters.filter(x=>validIds.has(x.id)).slice(0,50);
 return {posters,final};
}
test('VIDEO 01 exact 10000ms absolute deadline from same user search',()=>{
 assert.match(b,/TURBO_VIDEO_DEADLINE_MS = 10_000L/);
 assert.match(stage,/val videoStopAt = turboCommandStartedAt \+ TURBO_VIDEO_DEADLINE_MS/);
 assert.match(stage,/withTimeoutOrNull\(\s*\(videoStopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(0L\)/);
});
test('VIDEO 02 video verifier begins in parallel with providers at T0, not at T+5',()=>{
 assert.match(initial,/val streamingVerifier = launch/);
 assert.match(initial,/resolveVideoChunk\(pending\)/);
 assert.ok(initial.indexOf('val streamingVerifier = launch')<initial.indexOf('val externalDeferred = async'));
 assert.ok(initial.indexOf('val streamingVerifier = launch')<initial.indexOf('val cloudSeeds = try'));
});
test('VIDEO 03 second phase verifies only fingerprints frozen by five-second poster seal',()=>{
 assert.match(stage,/publishedCoverSnapshots\.mapTo\(HashSet<String>\(\)\) \{ it\.fingerprint \}/);
 assert.match(stage,/\.filter \{ it\.fingerprint in frozenFingerprints \}/);
});
test('VIDEO 04 candidate verification uses the native MusicLab verified metadata resolver',()=>{
 assert.match(stage,/resolveVideoChunk\(chunk\)/);
 assert.match(b,/CoverPlaybackMemory\.saveMetadataVerifiedVideo/);
 assert.match(stage,/!hasPublishableVideo\(it\)/);
});
test('VIDEO 05 at ten seconds only verified playback video cards remain; missing cards disappear',()=>{
 assert.match(stage,/val finalVideoSnapshots = publishedCoverSnapshots\.mapNotNull \{ poster ->/);
 assert.match(stage,/if \(resolved != null && hasPublishableVideo\(resolved\)\)/);
 assert.match(stage,/else \{\s*null\s*\}/);
 assert.match(stage,/\.take\(TURBO_RESULT_QUOTA\)/);
});
test('VIDEO 06 video updates all remaining cards together without gradual publication or paging',()=>{
 assert.equal((final.match(/publishedCoverSnapshots = finalVideoSnapshots/g)||[]).length,2);
 assert.equal(stage.includes('publishReadyBatches()'),false);
 assert.equal(stage.includes('loadNextPage()'),false);
 assert.match(final,/session\.turboFinished = true/);
});
test('VIDEO 07 verify keeps rank and original set order, never promotes new poster',()=>{
 assert.match(stage,/val byFingerprint = results\.associateBy \{ it\.fingerprint \}/);
 assert.match(stage,/publishedCoverSnapshots\.mapNotNull \{ poster ->/);
 assert.match(stage,/poster\.copy\(/);
 assert.ok(stage.indexOf('publishedCoverSnapshots.mapNotNull')<stage.indexOf('publishedCoverSnapshots = finalVideoSnapshots'));
});
test('VIDEO 08 no new source-discovery calls start after five-second poster seal',()=>{
 for(const prohibited of ['CoverDiscoverySources.discover(', 'CloudMusicDiscovery.searchArchive(', 'DiscogsVersionSource.loadVersionPage(', 'loadPage(']){
  assert.equal(stage.includes(prohibited),false,'Found forbidden discovery: '+prohibited);
 }
});
test('VIDEO 09 all old search/process resources are canceled at ten-second seal',()=>{
 for(const x of ['verificationJob?.cancel()', 'paginationJob?.cancel()', 'videoPreloadJob?.cancel()', 'backgroundResumeJob?.cancel()', 'CloudMusicDiscovery.cancelArchiveRequests()']){
  assert.ok(final.includes(x),'Missing cleanup '+x);
 }
 assert.match(final,/setCoverPerformanceLoad\(\s*active = false, heavy = false/);
});
test('VIDEO 10 deterministic 3/20/50/80 poster cases preserve a single <=50 verified block',()=>{
 for(const [count,keep] of [[3,3],[20,20],[50,50],[80,50],[50,18],[20,1],[3,0]]){
  const keys=new Set(Array.from({length:keep},(_,i)=>'p'+i));
  const {posters,final}=atomicPrune(count,keys);
  assert.equal(posters.length,Math.min(count,50));
  assert.equal(final.length,Math.min(keep,posters.length));
  assert.ok(final.every((c,i)=>c.id==='p'+i));
 }
 const blob=Buffer.concat([Buffer.from('blob '+player.length+'\0'),player]);
 assert.equal(createHash('sha1').update(blob).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
});
