import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const dir='app/src/main/kotlin/com/metrolist/music/ui/component/';
const b=readFileSync(dir+'DiscogsDirectVersionBrowser.kt','utf8');
const pub=readFileSync(dir+'CoverLab53Publication.kt','utf8');
const first=b.split('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02:')[1].split('} else {\n        searchJob =')[0];
const recovery=b.split('fun startDocumentaryRecovery()')[1].split('fun pauseCoverBackgroundForPlayback()')[0];

test('LAB54 fixes original writers empty in first Turbo admission',()=>{
 assert.match(first,/session\.originalWorkCredits = external\?\.workCredits\.orEmpty\(\)/);
 assert.match(first,/originalCredits = session\.originalWorkCredits/);
 assert.doesNotMatch(first,/originalYear = originalYear, originalCredits = emptyList\(\)/);
});
test('LAB54 retains original sixty-eight Discogs summaries for source-to-visible audit',()=>{
 assert.match(b,/val retainedCandidates = withContext\(Dispatchers\.Default\)[\s\S]*?CoverLab53Publication\.retain\(/);
 assert.match(first,/session\.turboCoverCandidatePool, results/);
 assert.match(b,/fun appendCatalogEvidence\(rows: List<DiscogsVersionSeed>\)/);
 assert.match(recovery,/appendCatalogEvidence\(details\)/);
 assert.match(recovery,/appendCatalogEvidence\(emptyList\(\)\)/);
 assert.match(recovery,/CoverLab55VisibleCandidates\.publish\(/);
});
test('LAB54 confirms original work credits without title-only or video-only admission',()=>{
 assert.match(recovery,/DiscogsVersionSource\.loadOriginalWorkCredits\(/);
 assert.match(recovery,/DiscogsVersionSource\.enrichSeedsForRanking\(/);
 assert.match(recovery,/it\.releaseId > 0 && !it\.discogsVerificationChecked/);
 assert.match(recovery,/CoverLab55VisibleCandidates\.publish\(/);
 assert.match(recovery,/CoverRoundStorage\.save\(context, sessionKey, expectedRound, saved\)/);
 assert.match(b,/CoverMultiProofVerifier\.verify\(/);
});
test('LAB54/LAB61 category counters use cumulative verified published list',()=>{
 assert.match(b,/results = if \(isTurbo && mode == DiscogsDirectMode\.COVER\) \{\s*unifiedCoverDisplay/);
 assert.match(b,/val visibleTrueCovers = remember\(/);
 assert.match(b,/publishedCoverSnapshots\s*\.filterNot\(::isHiddenForCurrentCover\)/);
 assert.match(b,/visibleTrueCovers\.size/);
});
test('LAB54 keeps playback priority while searching progressively for covers',()=>{
 assert.match(recovery,/while \(isActive && \(backgroundWorkBlocked\(\) \|\|/);
 assert.match(recovery,/isCoverAudioAtRisk\(\) == true/);
 assert.match(recovery,/pendingDetails\.chunked\(if \(playbackIsNormallyPlaying\(\)\) 1 else 3\)/);
 assert.match(recovery,/session\.turboRecoveryRoundPasses >=/);
 assert.doesNotMatch(recovery,/session\.turboRecoveryPasses >= 3/);
 assert.match(b,/coverRecoveryJob\?\.cancel\(\)/);
});
test('LAB54 never fills fifty with synthetic names and protects LAB45',()=>{
 assert.match(pub,/if \(eligibilityRequired && !eligibleCover\(seed\)\) return/);
 assert.match(pub,/const val COVER_QUOTA = 50/);
 assert.match(readFileSync(dir+'CoverRoundSearchPlan.kt','utf8'),/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
});
