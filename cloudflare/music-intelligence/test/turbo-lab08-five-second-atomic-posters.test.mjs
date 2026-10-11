import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const extract=(start,end)=>{
 const a=b.indexOf(start),z=b.indexOf(end,a+start.length);
 assert.ok(a>=0&&z>a,'Missing region '+start);return b.slice(a,z);
};
const turbo=extract('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const poster=extract('} finally {\n                    // Stop each discovery lane','// LAB08 Pollicino: from the poster seal');
const video=extract('// LAB08 Pollicino: from the poster seal','\n        } else {\n        searchJob =');
test('POSTER 01 a single 5000ms global discovery deadline applies to every provider',()=>{
 assert.match(b,/TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(turbo,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
 assert.match(turbo,/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
});
test('POSTER 02 each actual artwork/attached thumbnail is eligible WITHOUT video metadata verification',()=>{
 assert.match(poster,/val rankedPosters = coverCandidatePool\(\)\.filter\(::modeAcceptsSeed\)/);
 assert.match(poster,/!seed\.coverUrl\.isNullOrBlank\(\) \|\|\s*!seed\.resolvedVideoId\.isNullOrBlank\(\)/);
 assert.ok(!poster.includes('.filter(::hasPublishableVideo).take(TURBO_RESULT_QUOTA)'));
});
test('POSTER 03 ranks at most 50 posters and never creates fictitious blank cards',()=>{
 assert.match(poster,/val finalRanked = videoLookupPriority\(rankedPosters\.filter \{ seed ->[\s\S]*?\}\)\.take\(TURBO_RESULT_QUOTA\)/);
 assert.match(b,/TURBO_RESULT_QUOTA = 50/);
 assert.equal((poster.match(/publishedCoverSnapshots = finalRanked/g)||[]).length,2);
});
test('POSTER 04 all source callbacks already received are drained before disposing queues',()=>{
 assert.match(poster,/while \(true\) \{\s*val batch = supplementalSeeds\.tryReceive\(\)/);
 assert.match(poster,/sourceEvidence\.toList\(\)/);
 assert.match(poster,/mergePage\(results, bufferedSeeds \+ documentarySeeds, replace = false\)/);
 assert.ok(poster.indexOf('sourceEvidence.toList()')<poster.indexOf('streamingCandidates.cancel()'));
 assert.ok(poster.indexOf('supplementalSeeds.tryReceive()')<poster.indexOf('supplementalSeeds.cancel()'));
});
test('POSTER 05 no extra catalog discovery begins in the video-only phase',()=>{
 assert.equal(video.includes('CoverDiscoverySources.discover('),false);
 assert.equal(video.includes('loadPage('),false);
 assert.equal(video.includes('CloudMusicDiscovery.searchArchive('),false);
 assert.equal(video.includes('DiscogsVersionSource.loadVersionPage('),false);
});
test('POSTER 06 Compose reveals all posters after loading=false before videos are complete',()=>{
 assert.match(poster,/loading = false/);
 assert.match(poster,/session\.turboFinished = false/);
 assert.match(b,/mode == DiscogsDirectMode\.COVER && \(!isTurbo \|\| !loading\)/);
});
test('POSTER 07 only existing poster membership can survive; final sort restores 20-to-1 ranking',()=>{
 assert.match(video,/publishedCoverSnapshots\.mapNotNull \{ poster ->/);
 assert.match(video,/sortGroup\(/);
 assert.match(video,/DirectVersionSort\.RELEVANCE/);
 assert.match(video,/poster\.copy\(/);
 assert.equal(video.includes('take(visibleLimit + pageSize)'),false);
});
test('POSTER 08 next-ten legacy text and controls NEVER appear in Turbo mode',()=>{
 assert.match(b,/if \(!isTurbo && nextBlockPool\.isNotEmpty\(\)\)/);
 assert.match(b,/mode == DiscogsDirectMode\.COVER &&\s*!isTurbo &&\s*activeCriteria != null/);
 assert.match(b,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return\s*\n/);
});
test('POSTER 09 legacy timed batch publishers cannot restart after poster seal',()=>{
 assert.match(b,/suspend fun publishReadyBatches\(\) \{\s*(?:\/\/[^\n]*\n\s*)*if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return/);
 assert.match(b,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen\) \{\s*if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return@LaunchedEffect/);
 assert.match(b,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen, publishedCoverSnapshots\.size, visibleLimit\) \{\s*if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return@LaunchedEffect/);
});
test('POSTER 10 list remains one virtualized scroll with natural size 0..50',()=>{
 assert.match(b,/LazyColumn\(/);
 assert.match(b,/visibleLimit = TURBO_RESULT_QUOTA/);
 assert.match(b,/session\.visibleLimit = TURBO_RESULT_QUOTA/);
 assert.match(b,/Locandine: \$\{visibleTrueCovers\.size\}\/50/);
 assert.ok(!poster.includes('publishReadyBatches()'));
});
