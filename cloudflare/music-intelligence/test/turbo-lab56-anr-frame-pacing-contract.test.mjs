import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const browser=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const lab55=readFileSync(root+'CoverLab55VisibleCandidates.kt','utf8');

test('LAB56: no full-list canonical deduplication on the Android main looper during Cloud refill',()=>{
 const recovery=browser.split('suspend fun appendCatalogEvidence(rows: List<DiscogsVersionSeed>)')[1]
    .split('suspend fun appendVerifiedSource(')[0];
 assert.match(recovery,/val offMainPool = withContext\(Dispatchers\.Default\)/);
 assert.match(recovery,/CoverLab53Publication\.retain\(\s*poolBefore\.first, poolBefore\.second \+ rows/);
 assert.match(recovery,/session\.turboCoverCandidatePool === poolBefore\.first/);
 assert.match(recovery,/session\.turboCoverCandidatePool = candidatePool/);
});
test('LAB56: cached cover display prevents expensive canonical work on player ticks',()=>{
 assert.match(browser,/val historicalOrOriginal = remember\(/);
 assert.match(browser,/val publishedEligibleCovers = remember\(/);
 assert.match(browser,/val unifiedCoverDisplay = remember\(/);
 assert.match(browser,/val navigablePool = remember\(/);
 assert.match(browser,/val firstSectionFingerprints = remember\(historicalOrOriginal\)/);
 assert.match(browser,/val firstSectionBuckets = remember\(historicalOrOriginal\)/);
 assert.match(browser,/firstSectionBuckets\[DiscogsVersionSource\.crossSourceBucketKey\(seed\)\]/);
});
test('LAB56: restored Cloud candidate proof works without unnecessary multi-proof per-row checks',()=>{
 assert.match(browser,/if \(isTurbo\) !eligibleCoverForPublication\(seed\)/);
 assert.match(browser,/CoverLab55VisibleCandidates\.isRealCandidate\(/);
 assert.match(browser,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) \{/);
 assert.match(lab55,/const val QUOTA = 50/);
 assert.match(browser,/val initialCovers = CoverLab55VisibleCandidates\.publish\(/);
 assert.match(browser,/publishedCoverSnapshots = CoverLab55VisibleCandidates\.publish\(/);
 assert.match(browser,/Da verificare · registrazione candidata/);
});
test('LAB56: diagnostics no longer rescan hundreds of SHA-256 editorial keys for each callback',()=>{
 const part=browser.split('fun updateLab53PublicationDiagnostics()')[1]
    .split('fun isHiddenForCurrentCover(')[0];
 assert.match(part,/val foundCount = session\.turboCoverCandidatePool\.size/);
 assert.match(part,/val shownCount = publishedCoverSnapshots\.size/);
 assert.doesNotMatch(part,/CoverLab53Publication\.funnel\(/);
});
test('LAB56: LAB49 player, direct search and app identity remain untouched',()=>{
 assert.match(browser,/while \(backgroundWorkBlocked\(\) \|\|/);
 assert.match(browser,/isCoverAudioAtRisk\(\) == true/);
 assert.match(readFileSync(root+'CoverRoundSearchPlan.kt','utf8'),
   /if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
});
