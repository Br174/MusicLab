import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const b=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const n=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt','utf8');
const c=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const s=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const pc=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt','utf8');
const env=fs.readFileSync('uab-project.env','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const count=(s,t)=>s.split(t).length-1;
const ms=(k)=>{
  const m=b.match(new RegExp('private const val '+k+' = ([\\d_]+)L'));
  assert.ok(m,k);return Number(m[1].replaceAll('_',''));
};
const burst=()=>{
 const a=b.indexOf('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02');
 const z=b.indexOf('\n        } else {\n        searchJob =',a);
 assert.ok(a>=0 && z>a);return b.slice(a,z);
};
test('01 wall-clock Cover deadline is 5000ms from the user search action',()=>{
 assert.equal(ms('TURBO_BURST_DEADLINE_MS'),5000);
 assert.match(b,/val turboCommandStartedAt = SystemClock\.elapsedRealtime\(\)/);
 assert.match(b,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
 assert.match(b,/withTimeoutOrNull\(\s*\(stopAt - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1L\)/);
});
test('02 external providers receive a short concurrent window; partial callbacks survive',()=>{
 assert.ok(ms('TURBO_SOURCE_WAIT_MS')<=2500);
 assert.match(b,/onEarlyVideoCandidates = \{ quick ->\s*sourceEvidence\.addAll\(quick\)/);
 assert.match(b,/val externalSeeds = \(sourceEvidence\.toList\(\) \+/);
});
test('03 cloud archive never owns more than 1800ms of the global budget',()=>{
 assert.ok(ms('TURBO_ARCHIVE_TIMEOUT_MS')<=1800);
 assert.match(b,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
});
test('04 thumbnail primer reserves only 400ms within global deadline',()=>{
 assert.ok(ms('TURBO_THUMBNAIL_RESERVE_MS')<=400);
 assert.match(b,/val imageBudget = stopAt - SystemClock\.elapsedRealtime\(\)/);
 assert.match(b,/withTimeoutOrNull\(imageBudget\)/);
 assert.match(b,/Semaphore\(if \(playbackIsNormallyPlaying\(\)\) 2 else 4\)/);
});
test('05 audio wins: source and video concurrency scale down while playing',()=>{
 assert.match(b,/maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\)\) 2 else 5/);
 assert.match(b,/val verifyFanOut = if \(playbackIsNormallyPlaying\(\)\) 2 else 6/);
 assert.match(b,/heavy = !isTurbo && !playbackIsNormallyPlaying\(\)/);
});
test('06 published entries are certified video IDs, committed in one snapshot',()=>{
 assert.match(burst(),/val finalRanked = coverCandidatePool\(\)/);
 assert.match(burst(),/\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
 assert.match(burst(),/publishedCoverSnapshots = finalRanked/);
 assert.match(burst(),/session\.turboFinished = true/);
 assert.match(b,/if \(mode == DiscogsDirectMode\.COVER && \(!isTurbo \|\| !loading\)\)/);
});
test('07 all cover rows are one virtualized list without ten-at-a-time button',()=>{
 assert.match(b,/visibleLimit = if \(isTurbo && mode == DiscogsDirectMode\.COVER\) TURBO_RESULT_QUOTA else pageSize/);
 assert.match(b,/session\.visibleLimit = if \(isTurbo && mode == DiscogsDirectMode\.COVER\) TURBO_RESULT_QUOTA else pageSize/);
 assert.match(b,/LazyColumn\(/);
 assert.match(b,/mode == DiscogsDirectMode\.COVER &&\s*!isTurbo &&\s*activeCriteria != null/);
});
test('08 sealed job cleanup blocks network restarts and canceled searches never seal',()=>{
 for(const x of ['verificationJob?.cancel()','paginationJob?.cancel()','videoPreloadJob?.cancel()','backgroundResumeJob?.cancel()','CloudMusicDiscovery.cancelArchiveRequests()']){
  assert.ok(burst().includes(x),x);
 }
 assert.match(burst(),/if \(isActive && activeCriteria == criteria\)/);
 assert.match(b,/if \(!isTurbo && next == null && direction > 0/);
 assert.match(b,/if \(!isTurbo\) \{\s*scope\.launch\(Dispatchers\.IO\) \{\s*persistCloudPlaybackBinding/);
 assert.match(c,/continuation\.invokeOnCancellation \{ call\.cancel\(\) \}/);
});
test('09 HOME has native click in rail and bar, without duplicate release callback',()=>{
 assert.equal(count(n,'if (!isSearchItem) {'),2);
 assert.equal(count(n,'if (!isLongClick && !isHomeHoldItem) {'),2);
 assert.equal(count(n,'onItemClick(screen, currentIsSelected)'),4);
 assert.equal(count(n,'if (!isSearchItem && !isHomeHoldItem)'),0);
});
test('10 selected Cover plays directly with original audio engine and in-place signed family',()=>{
 assert.match(b,/connection\.playNow\(selectedItem\)/);
 assert.match(b,/connection\.service\.isPlayerReady\.value/);
 assert.match(pc,/fun playNow\(item: MediaItem\)/);
 assert.match(s,/fun playNow\(item: MediaItem\)/);
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1006"/);
 assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
 assert.match(env,/UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 06"/);
 const obj=Buffer.concat([Buffer.from('blob '+player.length+'\0'),player]);
 assert.equal(createHash('sha1').update(obj).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
});
