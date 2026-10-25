import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const r = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const b = readFileSync(r + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const m = readFileSync(r + 'CoverMultiProofVerifier.kt', 'utf8');
const mb = readFileSync(r + 'MusicBrainzCoverSource.kt', 'utf8');
const s = readFileSync(r + 'CoverDiscoverySources.kt', 'utf8');
const part=(a,z)=>b.slice(b.indexOf(a),b.indexOf(z,b.indexOf(a)));

test('LAB52 leaves the first LAB45 search path and LAB49 playback safety untouched',()=>{
 const round=readFileSync(r+'CoverRoundSearchPlan.kt','utf8');
 assert.match(round,/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
 assert.match(b,/while \(backgroundWorkBlocked\(\) \|\|/);
 assert.match(b,/playerConnection\?\.service\?\.isCoverAudioAtRisk\(\) == true/);
 assert.match(b,/coverRecoveryJob\?\.cancel\(\)/);
});
test('LAB52 creates a reproducible evidence trail, not title or video-only proof',()=>{
 assert.match(m,/val direct = CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
 assert.match(m,/DiscogsVersionSource\.sharedWorkCreditNames\(seed, originalCredits\)/);
 assert.match(m,/distinctSources\.count \{ it in catalogSources \} >= 2/);
 assert.match(m,/certified = direct \|\| credited/);
 assert.match(m,/keepForRetry = !direct && !credited/);
 assert.match(m,/if \(direct && titleRelation == TitleMeaningMatch\.DIFFERENT\)/);
 assert.match(m,/if \(credited && independentCatalogs\)/);
});
test('Recovered documentary candidates are pooled and reconsidered when evidence arrives',()=>{
 const recover=part('fun startDocumentaryRecovery()','fun pauseCoverBackgroundForPlayback()');
 assert.match(recover,/CoverMultiProofVerifier\.accumulate\(/);
 assert.match(recover,/pendingCoverProofs\.addAll\(remembered\.take\(300\)\)/);
 assert.match(recover,/session\.originalWorkCredits = authorCredits/);
 assert.match(recover,/CoverMultiProofVerifier\.verify\(/);
 assert.match(recover,/if \(!verdict\.certified\) null else seed\.copy/);
 assert.match(recover,/DiscogsVersionSource\.recordingIdentityKey\(seed\) !in occupied/);
 assert.match(recover,/seed\.resolvedVideoId\.trim\(\) !in usedVideoIds/);
});
test('MusicBrainz work identity includes ISWC without adding paid source',()=>{
 assert.match(mb,/val iswc: String\? = null/);
 assert.match(mb,/iswc-list > iswc/);
 assert.match(s,/if \(deepRecovery\) \{[\s\S]*?"SecondHandSongs", false, 0/);
});
test('LAB52 never modifies already published posters or starts another audio resolver',()=>{
 assert.match(b,/preserveInputOrder = true/);
 assert.match(b,/val expectedRound = coverRoundNumber/);
 assert.match(b,/coverRound = expectedRound/);
 assert.match(b,/CoverRoundStorage\.save\(context, sessionKey, expectedRound, saved\)/);
});
