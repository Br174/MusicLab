import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const d='app/src/main/kotlin/com/metrolist/music/ui/component/';
const b=readFileSync(d+'DiscogsDirectVersionBrowser.kt','utf8');
const p=readFileSync(d+'CoverLab55VisibleCandidates.kt','utf8');
const s=readFileSync(d+'CoverDiscoverySources.kt','utf8');
const r=readFileSync(d+'CoverRoundSearchPlan.kt','utf8');
test('LAB62: a new CoverLab navigation entry resets temporary history',()=>{
 assert.match(b,/val coverEntryId = remember \{/);
 assert.match(b,/navController\.currentBackStackEntry\?\.id/);
 assert.match(b,/append\("\|visit:"\)/);
 assert.match(b,/CoverRoundStorage\.save\(context, sessionKey, savedRound, oldRowsSnapshot\)/);
});
test('LAB62: recovery pages never restart at page one every round',()=>{
 assert.match(b,/var turboRecoveryRoundPasses: Int = 0/);
 assert.match(b,/session\.turboRecoveryRoundPasses\+\+/);
 assert.match(b,/coverRound = recoveryPass,/);
 assert.match(b,/if \(!repeatSameWork\) \{\s*session\.turboReserve = emptyList/);
 assert.doesNotMatch(b,/session\.turboRecoveryPasses >= 3/);
 assert.match(b,/if \(recovered != null && !documentaryRecoveryComplete/);
 assert.match(b,/backgroundWorkBlocked\(\)/);
 assert.match(b,/isCoverAudioAtRisk\(\)/);
});
test('LAB62: session-only same-video and same-recording duplicates excluded',()=>{
 assert.match(p,/history: List<DiscogsVersionSeed> = emptyList\(\)/);
 assert.match(p,/if \(identity in priorRecordings \|\| \(videoVerified && rawVideo in priorVideos\)\) continue/);
 assert.equal((b.match(/history = previousRoundRows,/g)||[]).length,5);
});
test('LAB62: COVER.INFO confirmed foreign work admitted on initial round; no video-only proof',()=>{
 assert.match(s,/candidate\.workRelationConfirmed &&\s*candidate\.sources\.any \{ it\.equals\("COVER.INFO", true\) \}/);
 assert.match(s,/val coverInfo = async\(Dispatchers\.IO\)/);
 assert.match(s,/if \(coverRound <= 1 && !deepRecovery/);
 assert.match(p,/TitleMeaningResolver\.matchesBaseTitle\(songTitle, seed\.trackTitle\)/);
 assert.match(p,/verifiedVideoIds == null \|\| rawVideo in verifiedVideoIds/);
 for(const x of ['2 -> talent','3 -> amateur','else -> longTail'])assert.ok(r.includes(x));
});
