import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const b=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const boost=readFileSync(root+'CoverLab74PositivePriority.kt','utf8');
const admission=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const old=readFileSync('cloudflare/music-intelligence/test/turbo-lab73-additive-restore.test.mjs','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const active=b.slice(b.indexOf('// LAB66 canonical Behavior Memory'),
  b.indexOf('// A further Cover round never clears'));

test('LAB74 is strictly positive: no new source-based exclusion or mandatory release proof',()=>{
 for(const forbidden of ['CoverLab72PublicationProof','flushFirstRound','firstRoundVideos',
   'professionalUnreleased','isReleaseProof','withDocumentedRelease'])
   assert.ok(!active.includes(forbidden),'LAB72 veto must not reappear: '+forbidden);
 assert.match(boost,/NO(?:T)?[^\n]*admission condition/i);
 assert.doesNotMatch(boost,/return false|throw IllegalArgumentException|require\(/);
 assert.match(boost,/fun order\(/);
 assert.match(boost,/\.thenBy \{ it.index \}/);
 assert.match(active,/suspend fun tryPublish\(candidate: CoverSourceCandidate, searchedVideo: SongItem\? = null\)/);
 assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
});

test('Documentary and video candidate ordering preserve every item',()=>{
 assert.match(active,/CoverLab74PositivePriority\.order\(/);
 assert.match(active,/candidates, originalArtist/);
 assert.match(active,/\.forEach\(::submitSourceVideo\)/);
 assert.match(active,/CoverLab74PositivePriority\.videoScore\(/);
 assert.match(active,/if \(thisRound != 1\) all else all.withIndex\(\)/);
 assert.match(active,/\.map \{ it.value \}/);
 assert.match(active,/val verdict = CoverLabTurboRoundAdmission\.evaluate\(/);
 assert.match(admission,/round <= 1 -> strong/);
 assert.match(admission,/round == 2 -> medium/);
 assert.match(admission,/round == 3 -> weak/);
});

test('Promote original performer, studio discs and foreign COVER.INFO only by bonus',()=>{
 for(const part of ['"cover.info" in sources','"discogs" in sources && publishedHint',
   '"apple/itunes" in sources || "spotify" in sources',
   'candidate.workRelationConfirmed','TitleMeaningResolver.sameArtist',
   'studioMarkers.containsMatchIn','professionallyRecorded.containsMatchIn'])
   assert.ok(boost.includes(part),part);
 assert.match(active,/album = record\.releaseTitle/);
 assert.match(boost,/return points/);
 assert.match(boost,/return score/);
});

test('All original LAB73 gates and audio priority remain unchanged',()=>{
 assert.match(active,/CoverLab71FoundationGate\.isDuplicate\(seed, priorEntries\)/);
 assert.match(active,/CoverLab71FoundationGate\.withinOriginalCaps\(/);
 assert.match(active,/backgroundWorkBlocked\(\)/);
 assert.match(active,/isCoverAudioAtRisk\(\)/);
 assert.match(active,/CoverLab71FoundationGate\.isForbidden\(/);
 assert.match(admission,/CoverLab71FoundationGate\.matchesRequestedWork\(/);
 assert.match(old,/Discogs broadened candidates do NOT gate Youtube or COVER.INFO/);
});

test('User-approved priority principle is the latest canonical LAB behavior rule',()=>{
 const v6=memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v6');
 assert.equal(memory.canonicalBehaviorRevision,'2026-10-27-lab74-positive-priority-no-new-veto-v6');
 assert.ok(v6 && v6.status==='active' && v6.protected);
 assert.equal(memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v5').status,'retired');
 for(const d of memory.conflictDispositions){
   assert.equal(memory.rules.find(r=>r.id===d.oldRule).status,'retired',d.oldRule);
   assert.equal(memory.rules.find(r=>r.id===d.replacementRule).status,'active',d.replacementRule);
 }
});
