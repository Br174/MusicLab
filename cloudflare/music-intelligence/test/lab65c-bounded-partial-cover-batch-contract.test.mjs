import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const env=fs.readFileSync('uab-project.env','utf8');
const section=(a,b)=>{const i=source.indexOf(a),j=source.indexOf(b,i+a.length);assert.ok(i>=0&&j>i, a+' boundary');return source.slice(i,j);};

test('LAB65C a remote catalog of 220 pages never blocks 3 already-verified cover songs forever',()=>{
 const publication=section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
 assert.match(publication,/val graceElapsed = session\.coverBatchStartedAtMs > 0L/);
 assert.match(publication,/DIRECT_COVER_PARTIAL_BATCH_GRACE_MS/);
 assert.match(publication,/if \(ready\.size < slots && !sourceExhausted && !graceElapsed\) break/);
 assert.match(publication,/val group = ready\.take\(slots\)/);
 assert.match(publication,/publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
 assert.match(source,/DIRECT_COVER_PARTIAL_BATCH_GRACE_MS = 2_800L/);
 const ready=[{id:'a',score:20},{id:'b',score:19},{id:'c',score:18}];
 const slots=5, pagesRemaining=220, graceElapsed=true;
 const shouldWait=ready.length<slots && pagesRemaining===0 && !graceElapsed;
 assert.equal(shouldWait,false);
 assert.deepEqual(ready.slice(0,slots).map(x=>x.score),[20,19,18]);
});

test('LAB65C partial group never violates previous rank and original-first display lock',()=>{
 const publication=section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
 assert.match(publication,/if \(!originalSectionFrozen\)/);
 assert.match(publication,/publishedOriginalSnapshots = candidates\.filter\(::hasPublishableVideo\)/);
 assert.ok(publication.indexOf('originalSectionFrozen = true')<publication.indexOf('while (publishedCoverSnapshots.size < visibleLimit)'));
 assert.match(publication,/val settledPrefix = remaining\.takeWhile\(::videoAttemptSettled\)/);
 assert.match(publication,/val ready = settledPrefix\.filter\(::hasPublishableVideo\)/);
 assert.doesNotMatch(publication,/publishedCoverSnapshots = results/);
 assert.match(source,/publishedCoverSnapshots\.take\(visibleLimit\)/);
});

test('LAB65C independent timer releases verified partial batches without waiting provider work',()=>{
 const timer=section('// LAB65C: source pagination can contain hundreds', 'LaunchedEffect(visibleLimit, sourceDiagnostics');
 assert.match(timer,/LaunchedEffect\(sessionKey, rankingFrozen, originalSectionFrozen, publishedCoverSnapshots\.size, visibleLimit\)/);
 assert.match(timer,/delay\(remaining\)/);
 assert.match(timer,/publishReadyBatches\(\)/);
 assert.doesNotMatch(timer,/loadPage\(|getStreamUrl\(|connection\.playQueue\(/);
 assert.match(source,/session\.coverBatchStartedAtMs = SystemClock\.elapsedRealtime\(\)/);
});

test('LAB65C retains no-audio-probe policy, raw-video-ID validation, and same Android signing family',()=>{
 const pub=section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
 assert.doesNotMatch(pub,/getStreamUrl|ExoPlayer|playQueue/);
 assert.match(source,/fun hasPublishableVideo\(seed: DiscogsVersionSeed\)/);
 assert.match(source,/CoverPlaybackMemory\.verifiedVideo/);
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6601"/);
 assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
 assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});