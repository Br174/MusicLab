import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const sources=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
function between(source,start,end){
 const a=source.indexOf(start);
 const b=source.indexOf(end,a+start.length);
 assert.ok(a>=0 && b>a,'Expected code region '+start);
 return source.slice(a,b);
}
const admit=between(browser,'fun modeAcceptsSeed(seed: DiscogsVersionSeed)','fun isOriginalPerformerVersion');
const resolver=between(browser,'val streamingVerifier = launch','val archiveDeferred = async');
const burst=between(browser,'if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const coverInfo=between(sources,'private suspend fun discoverCoverInfo(','private fun toCoverInfoCandidate');

test('WIN 01 global deadline never changes from the user-set 5000ms',()=>{
 assert.match(browser,/TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(burst,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
});
test('WIN 02 COVER.INFO has enough independent time for search + detail page, still under 5000ms',()=>{
 assert.match(browser,/TURBO_COVER_INFO_WAIT_MS = 4_100L/);
 assert.match(burst,/withTimeoutOrNull\(TURBO_COVER_INFO_WAIT_MS\)/);
 assert.match(burst,/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
});
test('WIN 03 old slow cloud lanes keep shorter waits rather than stealing COVER.INFO time',()=>{
 assert.match(browser,/TURBO_SOURCE_WAIT_MS = 2_500L/);
 assert.match(browser,/TURBO_ARCHIVE_TIMEOUT_MS = 1_800L/);
});
test('WIN 04 translated titles are admitted only when COVER.INFO confirms work relation',()=>{
 assert.match(admit,/seed\.workRelationConfirmed &&\s*seed\.sourceNames\.any \{ it\.equals\("COVER.INFO", ignoreCase = true\) \}/);
 assert.match(admit,/return independentServices >= 2 \|\|/);
});
test('WIN 05 first search still requires work-title match, preventing arbitrary songs',()=>{
 assert.match(coverInfo,/sameBaseTitle\(title, seed\.title\) &&\s*modeAcceptsArtist/);
});
test('WIN 06 related versions enter early callback even when titles are foreign translations',()=>{
 assert.match(coverInfo,/val partial = seeds\.filter \{ seed ->\s*seed\.directRelation &&\s*modeAcceptsArtist/);
 assert.match(coverInfo,/if \(partial\.isNotEmpty\(\)\) onEarlyVideoCandidates\(partial\)/);
});
test('WIN 07 streaming verifier checks source-attached YouTube IDs before blind lookups',()=>{
 assert.match(resolver,/compareByDescending<DiscogsVersionSeed> \{\s*!it\.resolvedVideoId\.isNullOrBlank\(\)/);
 assert.match(resolver,/resolveVideoChunk\(pending\)/);
});
test('WIN 08 final fallback video resolver also prioritizes already present video IDs',()=>{
 assert.match(burst,/val candidates = coverCandidatePool\(\)/);
 assert.match(burst,/\.sortedWith\(\s*compareByDescending<DiscogsVersionSeed> \{\s*!it\.resolvedVideoId\.isNullOrBlank\(\)/);
});
test('WIN 09 audio buffering uses two lanes, idle device may use eight lanes',()=>{
 assert.match(browser,/fun playbackIsCritical\(\): Boolean/);
 assert.match(browser,/Player\.STATE_BUFFERING/);
 assert.match(burst,/val verifyFanOut = if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 8/);
 assert.match(resolver,/take\(if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 8\)/);
});
test('WIN 10 source, image and video concurrency independently honor buffering priority',()=>{
 assert.match(burst,/maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 5/);
 assert.match(burst,/Semaphore\(if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 4\)/);
 assert.match(burst,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
 assert.match(burst,/session\.turboFinished = true/);
});
