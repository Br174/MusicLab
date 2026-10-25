import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const file='app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt';
const screen=readFileSync(file,'utf8');
const section=(start,end)=>screen.split(start)[1]?.split(end)[0]??'';
const recovery=section('fun startDocumentaryRecovery()','fun pauseCoverBackgroundForPlayback()');
const init=section('val session = remember(sessionKey) {','// LAB12: protect UI/audio');
const snapshots=section('val session = remember(sessionKey) {','DisposableEffect(sessionKey)');
const work=section('suspend fun appendVerifiedSource(rows: List<CoverSourceCandidate>)','// LAB53: restore qualified cached candidates');
const catalog=section('suspend fun appendCatalogEvidence(rows: List<DiscogsVersionSeed>)','val proofAppendMutex = Mutex()');

test('ANR root: no synchronized JSON/disk CoverRoundStorage.load in Compose remember',()=>{
 assert.doesNotMatch(init,/CoverRoundStorage\.load\(/);
 assert.match(snapshots,/val saved = withContext\(Dispatchers\.IO\)/);
 assert.match(snapshots,/CoverRoundStorage\.load\(context, sessionKey\)/);
 assert.match(snapshots,/restorationReady = true/);
});
test('cold-start restoration finishes before any first-search attempt',()=>{
 assert.match(screen,/var restorationReady by remember\(sessionKey\)/);
 assert.match(screen,/LaunchedEffect\(sessionKey, discogsToken, restorationReady\)/);
 assert.match(screen,/if \(!restorationReady\) return@LaunchedEffect/);
 assert.match(snapshots,/session\.previousRoundRows = saved\.rows/);
 assert.match(snapshots,/previousRoundRows = saved\.rows/);
});
test('LAB55 retains all Cloud cover provenance and fifty distinct poster slots',()=>{
 const lab55=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab55VisibleCandidates.kt','utf8');
 assert.match(lab55,/const val QUOTA = 50/);
 assert.match(screen,/val initialCovers = CoverLab55VisibleCandidates\.publish/);
 assert.match(screen,/publishedCoverSnapshots = CoverLab55VisibleCandidates\.publish/);
 assert.match(screen,/Da verificare · registrazione candidata/);
 assert.match(recovery,/CloudMusicDiscovery\.discoverMemoryState\(/);
});
test('poster recovery no longer loops 750 work-identity checks simply to log',()=>{
 assert.doesNotMatch(catalog,/val eligible = candidatePool\.filter/);
 assert.doesNotMatch(catalog,/eligibleCoverForPublication\(seed\)/);
 assert.match(catalog,/val offMainPool = withContext\(Dispatchers\.Default\)/);
});
test('300 per-source documentary proofs and canonical merges never run on Main',()=>{
 assert.match(recovery,/val proofAppendMutex = Mutex\(/);
 assert.match(work,/proofAppendMutex\.lock\(\)/);
 assert.match(work,/proofAppendMutex\.unlock\(\)/);
 assert.match(work,/val recoveredSeeds = withContext\(Dispatchers\.Default\)/);
 assert.match(work,/val completePool = withContext\(Dispatchers\.Default\)/);
 assert.match(work,/val accepted = withContext\(Dispatchers\.Default\)/);
 assert.match(work,/val mergedResults = withContext\(Dispatchers\.Default\)/);
});
test('background publishing must not overwrite newer screen state',()=>{
 assert.match(work,/publishedCoverSnapshots !== previous/);
 assert.match(work,/results !== resultsSnapshot/);
 assert.match(work,/session\.publishedCoverSnapshots = accepted/);
 assert.match(work,/session\.results = mergedResults/);
});
test('existing LAB56 cache and first section indexes still prevent video tick recomputation',()=>{
 assert.match(screen,/val historicalOrOriginal = remember\(/);
 assert.match(screen,/val publishedEligibleCovers = remember\(/);
 assert.match(screen,/val firstSectionBuckets = remember\(historicalOrOriginal\)/);
});
test('untouched direct search and player compatibility contracts',()=>{
 assert.match(readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt','utf8'),/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
 assert.match(screen,/isCoverAudioAtRisk\(\) == true/);
 assert.match(screen,/while \(backgroundWorkBlocked\(\) \|\|/);
});

test('Coil image loader never blocks main waiting for DataStore during first 50 covers',()=>{
 const app=readFileSync('app/src/main/kotlin/com/metrolist/music/App.kt','utf8');
 const loader=app.split('override fun newImageLoader(context: PlatformContext): ImageLoader {')[1]
     .split('private object CrashSafeInterceptor')[0];
 assert.match(loader,/val cacheSize = cachedCoilCacheSize \?: 512/);
 assert.doesNotMatch(loader,/\\brunBlocking\\s*\\{/);
 assert.match(app,/applicationScope\.launch\(Dispatchers\.IO\) \{\s*cachedCoilCacheSize =/);
});
