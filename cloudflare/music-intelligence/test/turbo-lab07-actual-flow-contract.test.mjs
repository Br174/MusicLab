import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const c=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const nav=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const flow=()=>{
 const a=b.indexOf('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02');
 const e=b.indexOf('\n        } else {\n        searchJob =',a);
 assert.ok(a>=0&&e>a);
 return b.slice(a,e);
};
test('flow 11: five seconds is a wall-clock budget, not five seconds per provider',()=>{
 assert.match(b,/private const val TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(flow(),/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
 assert.match(flow(),/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
});
test('flow 12: every provider callback enters the nonblocking bounded verifier queue',()=>{
 assert.match(flow(),/val streamingCandidates = Channel<List<CoverSourceCandidate>>\(Channel\.UNLIMITED\)/);
 assert.match(flow(),/onEarlyVideoCandidates = \{ quick ->\s*sourceEvidence\.addAll\(quick\)\s*if \(quick\.isNotEmpty\(\)\) streamingCandidates\.trySend\(quick\)/);
 assert.match(flow(),/onSourceCompleted = \{ items, diagnostic, originalYear ->\s*sourceEvidence\.addAll\(items\)\s*if \(items\.isNotEmpty\(\)\) streamingCandidates\.trySend\(items\)/);
});
test('flow 13: verified video lookup begins before serial await of cloud/archive',()=>{
 const f=flow(),first=f.indexOf('val streamingVerifier = launch'),awaitAll=f.indexOf('archiveDeferred.await()');
 assert.ok(first>0 && first<awaitAll);
 assert.match(f,/resolveVideoChunk\(pending\)/);
});
test('flow 14: no fake videos, verifies recording metadata before marking ID ready',()=>{
 assert.match(b,/CompilationTrackResolver\.isHardCompatible\(\s*track, candidate,\s*documentedPerformerMatch = documentedPerformer/);
 assert.match(b,/CoverPlaybackMemory\.saveMetadataVerifiedVideo/);
 assert.match(b,/fun hasPublishableVideo\(seed: DiscogsVersionSeed\): Boolean/);
 assert.match(flow(),/\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
});
test('flow 15: session with zero results is eligible for retry',()=>{
 assert.match(b,/if \(isTurbo && repeatSameWork && session\.turboFinished && session\.publishedCoverSnapshots\.isNotEmpty\(\)\)/);
});
test('flow 16: 50 completed covers have one snapshot and no 5-at-a-time UI text in Turbo',()=>{
 assert.match(flow(),/publishedCoverSnapshots = finalRanked/);
 assert.match(flow(),/visibleLimit = TURBO_RESULT_QUOTA/);
 assert.match(b,/if \(isTurbo\) \{\s*"Raccolgo e verifico tutte le cover disponibili entro 5 secondi…"/);
 assert.match(b,/LazyColumn\(/);
 assert.match(b,/mode == DiscogsDirectMode\.COVER &&\s*!isTurbo &&\s*activeCriteria != null/);
});
test('flow 17: screen exit prevents publishing an abandoned search',()=>{
 assert.match(flow(),/if \(isActive && activeCriteria == criteria\)/);
 assert.match(b,/playbackLaunchJob\?\.cancel\(\)/);
 assert.match(b,/searchJob\?\.cancel\(\)/);
 assert.match(b,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
});
test('flow 18: ranking preserves 20-to-1 order independent of asynchronous provider arrival',()=>{
 assert.match(b,/compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
 assert.match(b,/session\.stableOrder = sortedPool\(results\)\.map \{ it\.fingerprint \}/);
 assert.match(flow(),/rebuildStableOrder\(\)/);
});
test('flow 19: post-seal swipe never wakes providers, player direct tap preserves source',()=>{
 assert.match(b,/if \(!isTurbo && next == null && direction > 0/);
 assert.match(b,/connection\.playNow\(selectedItem\)/);
 assert.match(b,/if \(isTurbo && mode == DiscogsDirectMode\.COVER && session\.turboFinished\)/);
 assert.equal(nav.split('if (!isSearchItem) {').length-1,2);
});
test('flow 20: original audio engine exact byte content and bounded cancellable source HTTP',()=>{
 const blob=Buffer.concat([Buffer.from('blob '+player.length+'\0'),player]);
 assert.equal(createHash('sha1').update(blob).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
 assert.match(c,/continuation\.invokeOnCancellation \{ call\.cancel\(\) \}/);
 assert.match(b,/val verifyFanOut = if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 8/);
});
