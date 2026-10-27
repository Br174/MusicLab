import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const browse=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const queries=readFileSync(root+'CoverRoundSearchPlan.kt','utf8');
const gate=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const active=browse.slice(browse.indexOf('// LAB66 canonical Behavior Memory'),
 browse.indexOf('// A further Cover round never clears'));

test('LAB73 is explicitly additive, LAW: never reintroduce LAB72 empty-results gates',()=>{
 assert.equal(memory.canonicalBehaviorRevision,'2026-10-27-lab73-additive-discovery-restore-lab71-v5');
 assert.equal(memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v4').status,'retired');
 assert.equal(memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v5').status,'active');
 for(const forbidden of ['CoverLab72PublicationProof','flushFirstRound','firstRoundVideos',
     'professionalUnreleased','isReleaseProof','discographyProofs','withDocumentedRelease'])
   assert.ok(!active.includes(forbidden),'Forbidden LAB72 veto: '+forbidden);
 assert.match(active,/suspend fun tryPublish\(candidate: CoverSourceCandidate, searchedVideo: SongItem\? = null\)/);
 assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
});
test('round 1, round 2 and round 3 retain LAB71 admission thresholds untouched',()=>{
 assert.match(gate,/round <= 1 -> strong/);
 assert.match(gate,/round == 2 -> medium/);
 assert.match(gate,/round == 3 -> weak/);
 assert.match(gate,/else -> containsTitle \|\| exactTitle \|\| relation/);
 assert.match(active,/val verdict = CoverLabTurboRoundAdmission\.evaluate\(/);
 assert.match(active,/CoverLab71FoundationGate\.isDuplicate\(seed, priorEntries\)/);
 assert.match(active,/CoverLab71FoundationGate\.withinOriginalCaps\(/);
});
test('broader search phrases are ADDITIONS across all tiers, no lost LAB71 keys',()=>{
 for (const word of ['official cover','famous cover version','foreign language cover',
   'cover album','discografia cover','compilation cover','versione straniera cover',
   'Festival di Sanremo cover','X Factor cover live','The Voice cover live',
   'independent artist cover','amateur cover','indie singer cover'])
   assert.ok(queries.includes(word),'Missing key '+word);
 assert.match(queries,/Query\("\$clean \$artist", AiCoverCategory\.COVER\)/);
 assert.match(queries,/distinctBy \{ it\.text\.trim\(\)\.lowercase\(\) \}/);
});
test('Discogs broadened candidates do NOT gate Youtube or COVER.INFO',()=>{
 assert.match(active,/val discogs = if \(includeDocumentary && thisRound == 1 &&/);
 assert.match(active,/discogsToken\.isNotBlank\(\)/);
 assert.match(active,/perPage = 50/);
 assert.match(active,/checked >= 8/);
 assert.doesNotMatch(active,/TitleMeaningResolver\.sameArtist\(\s*record\.artist, originalArtist/);
 assert.match(active,/val youtube = launch \{/);
 assert.match(active,/val documents = if \(includeDocumentary\)/);
 assert.match(active,/onEarlyVideoCandidates = \{ candidates ->/);
 assert.match(active,/val sourceVideoGate = Semaphore\(2\)/);
});
test('audio player priority and no karaoke/wrong-song guard unchanged',()=>{
 assert.match(active,/backgroundWorkBlocked\(\)/);
 assert.match(active,/isCoverAudioAtRisk\(\)/);
 assert.match(active,/CoverLab71FoundationGate\.isForbidden\(/);
 assert.match(gate,/CoverLab71FoundationGate\.matchesRequestedWork\(/);
 assert.doesNotMatch(active,/DELETE FROM|DROP TABLE/);
});
