import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const read=file=>readFileSync(root+file,'utf8');
const b=read('DiscogsDirectVersionBrowser.kt');
const p=read('CoverLab53Publication.kt');
const recovery=b.split('fun startDocumentaryRecovery()')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const publisher=b.split('fun sealPostersOnce()')[1].split('fun stopAtFullQuota()')[0];
const finallyPublisher=b.split('val finalRanked = turboRankedPool()')[1].split('// LAB46: preserve LAB45')[0];

test('50 is a cover-only quota, originals in independent section',()=>{
 assert.match(p,/const val COVER_QUOTA = 50/);
 assert.match(p,/published\.forEach \{ accept\(it, eligibilityRequired = false\) \}/);
 assert.match(p,/candidates\.forEach \{ accept\(it, eligibilityRequired = true\) \}/);
 assert.match(p,/if \(eligibilityRequired && !eligibleCover\(seed\)\) return/);
 assert.match(publisher,/val committedCount = publishedCoverSnapshots\.size/);
 assert.match(finallyPublisher,/filter\(::isDistinctOriginalVersion\)\.take\(10\)/);
 assert.match(finallyPublisher,/publishedCoverSnapshots = CoverLab55VisibleCandidates\.publish\(/);
 assert.doesNotMatch(finallyPublisher,/TURBO_RESULT_QUOTA -\s*publishedOriginalSnapshots\.size/);
});
test('the first ten and next partial covers actually publish before full quota',()=>{
 assert.match(publisher,/initialCovers\.take\(TURBO_MIN_EARLY_POSTER_BLOCK\)/);
 assert.match(publisher,/val next = CoverLab55VisibleCandidates\.publish\(/);
 assert.doesNotMatch(publisher,/if \(next\.size < TURBO_RESULT_QUOTA - committedCount\) return/);
 assert.match(publisher,/if \(next\.isEmpty\(\)\) return/);
 assert.match(b,/if \(publishedCoverSnapshots\.size < TURBO_RESULT_QUOTA\) return false/);
});
test('the initial search LAB45 stays intact and no video ID is needed for visual publication',()=>{
 assert.match(read('CoverRoundSearchPlan.kt'),/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
 assert.match(p,/A missing video ID leaves the genuine recording as a visible candidate/);
 assert.match(b,/poster publication NEVER depends on a linked video/i);
 assert.match(b,/val ready = turboRankedPool\(\)/);
});
test('source candidates are retained after short burst and reused on recovery',()=>{
 assert.match(b,/session\.turboCoverCandidatePool = CoverLab53Publication\.retain\(/);
 assert.match(recovery,/appendVerifiedSource\(emptyList\(\)\)/);
 assert.match(recovery,/CoverLab53Publication\.retain\(/);
 assert.match(recovery,/val completePool = withContext\(Dispatchers\.Default\)/);
 assert.match(recovery,/CoverLab55VisibleCandidates\.publish\(/);
});
test('bounded progressive refill yields to LAB49 audio and does not spin forever',()=>{
 assert.match(recovery,/session\.turboRecoveryPasses >= 3/);
 assert.match(recovery,/while \(backgroundWorkBlocked\(\) \|\|/);
 assert.match(recovery,/isCoverAudioAtRisk\(\) == true/);
 assert.match(recovery,/maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\)\) 1 else 2/);
 assert.match(recovery,/forceRefresh = recoveryPass > 1/);
 assert.match(recovery,/coverRound = expectedRound \+ recoveryPass - 1/);
 assert.match(b,/coverRecoveryJob\?\.cancel\(\)/);
});
test('funnel diagnostics account for the actually displayed list, not just found count',()=>{
 assert.match(p,/trovate=\$found/);
 assert.match(p,/certificate=\$certified/);
 assert.match(p,/escluse=\$excluded/);
 assert.match(p,/mostrate=\$visible/);
 assert.match(b,/da verificare=\$\{visibleTrueCovers\.size - confirmed\}/);
});
test('documented sources remain authoritative; LAB52 composer proof is an alternative, not a guess',()=>{
 assert.match(b,/CoverMultiProofVerifier\.verify\(/);
 assert.match(b,/CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
 assert.match(b,/if \(isTurbo\) !eligibleCoverForPublication\(seed\)/);
 assert.match(b,/else !modeAcceptsSeed\(seed\)/);
 assert.match(p,/DiscogsVersionSource\.recordingIdentityKey\(seed\)/);
 assert.match(p,/if \(videoId\.isNotEmpty\(\) && videoId in usedVideos\) return/);
});
