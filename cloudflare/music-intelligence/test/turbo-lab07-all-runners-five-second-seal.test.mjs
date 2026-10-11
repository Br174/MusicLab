import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const sources=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
function between(s,start,end){
 const i=s.indexOf(start),j=s.indexOf(end,i+start.length);
 assert.ok(i>=0 && j>i,'Missing section: '+start);
 return s.slice(i,j);
}
const turbo=between(b,'if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const provider=between(sources,'suspend fun discover(', 'private suspend fun discoverCoverInfo(');
const runners=between(turbo,'val streamingVerifier = launch','providerJobs.addAll(');
const finish=turbo.slice(turbo.lastIndexOf('} finally {'));
test('RUN 01 all provider runner lanes are spawned before aggregator awaits',()=>{
 const laneCalls=['val archiveDeferred = async(Dispatchers.IO)',
  'val memoryDeferred = async(Dispatchers.IO)','val firstPageDeferred = async',
  'val externalDeferred = async(Dispatchers.IO)'];
 const awaits='val cloudSeeds = try {';
 const p=turbo.indexOf(awaits);assert.ok(p>0);
 for(const lane of laneCalls) assert.ok(turbo.indexOf(lane)>0 && turbo.indexOf(lane)<p,lane);
});
test('RUN 02 COVER.INFO, MusicBrainz, Apple/iTunes, Spotify, LRCLIB and native cache share the same operation',()=>{
 for(const lane of ['val coverInfo = async(Dispatchers.IO)',
 'val musicBrainzLookup =','val iTunes = async(Dispatchers.IO)',
 'val spotify = async(Dispatchers.IO)','val lrcLib = async(Dispatchers.IO)']){
  assert.ok(provider.includes(lane),'Missing '+lane);
 }
 assert.match(turbo,/CloudMusicDiscovery\.cachedMemoryState/);
 assert.match(turbo,/CloudMusicDiscovery\.searchArchive/);
 assert.match(turbo,/loadPage\(criteria, page = 1/);
});
test('RUN 03 Cover.Info has independent priority but auxiliary sources remain active',()=>{
 assert.match(provider,/val coverInfo = async\(Dispatchers\.IO\)/);
 assert.match(provider,/val sourceGate = Semaphore/);
 assert.match(turbo,/TURBO_COVER_INFO_WAIT_MS/);
 assert.match(turbo,/maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 5/);
});
test('RUN 04 global time is one shared five-second budget, not five seconds per lane',()=>{
 assert.match(b,/TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(turbo,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
 assert.match(turbo,/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
});
test('RUN 05 Cloud archive and internal memory hand candidates to verifier immediately',()=>{
 assert.match(turbo,/val supplementalSeeds = Channel<List<DiscogsVersionSeed>>\(Channel\.UNLIMITED\)/);
 assert.match(runners,/if \(seeds\.isNotEmpty\(\)\) supplementalSeeds\.trySend\(seeds\)/);
 assert.match(runners,/if \(known\.isNotEmpty\(\)\) supplementalSeeds\.trySend\(known\)/);
 assert.match(runners,/val cloudSeedsNow = supplementalSeeds\.tryReceive\(\)\.getOrNull\(\)\.orEmpty\(\)/);
});
test('RUN 06 provider callbacks queue each candidate independently; slow providers cannot gate the verifier',()=>{
 assert.match(runners,/streamingCandidates\.trySend\(fresh\)/);
 assert.match(runners,/streamingCandidates\.trySend\(fresh\)/);
 assert.match(runners,/val streamingVerifier = launch/);
 assert.ok(runners.indexOf('val streamingVerifier = launch') < runners.indexOf('val externalDeferred = async'));
});
test('RUN 07 Discogs-only results are also verified without waiting for another source callback',()=>{
 assert.match(runners,/val early = withTimeoutOrNull\(110L\)/);
 assert.match(runners,/val pending = coverCandidatePool\(\)/);
 assert.match(runners,/val examinedBindings = hashSetOf<String>\(\)/);
 assert.match(runners,/key !in examinedBindings/);
});
test('RUN 08 verified video metadata and ranking gate all published records',()=>{
 assert.match(b,/CoverPlaybackMemory\.saveMetadataVerifiedVideo/);
 assert.match(turbo,/val finalRanked = coverCandidatePool\(\)\s*\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
 assert.match(b,/private const val TURBO_RESULT_QUOTA = 50/);
});
test('RUN 09 publication is atomic and contains one scroll, without ten-at-a-time',()=>{
 assert.match(turbo,/publishedCoverSnapshots = finalRanked/);
 assert.match(turbo,/visibleLimit = TURBO_RESULT_QUOTA/);
 assert.match(b,/LazyColumn\(/);
 assert.match(b,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return/);
});
test('RUN 10 after publishing: cancel idle jobs, HTTP, queues and reset audio priority',()=>{
 for(const marker of ['streamingCandidates.cancel()', 'supplementalSeeds.cancel()',
 'verificationJob?.cancel()', 'paginationJob?.cancel()', 'videoPreloadJob?.cancel()',
 'backgroundResumeJob?.cancel()', 'CloudMusicDiscovery.cancelArchiveRequests()']){
  assert.ok(finish.includes(marker),'Missing shutdown '+marker);
 }
 assert.match(finish,/setCoverPerformanceLoad\(\s*active = false, heavy = false/);
});
