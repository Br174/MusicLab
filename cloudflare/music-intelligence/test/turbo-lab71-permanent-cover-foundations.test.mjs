import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const b = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const gate = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGate.kt','utf8');
const admission = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt','utf8');
const plan = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt','utf8');
const memory = JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const active=b.slice(b.indexOf('// LAB66 canonical Behavior Memory'),b.indexOf('// A further Cover round never clears'));
const required=[
'musiclab.cover.work-identity-progressive.v3',
'musiclab.cover.source-and-category-rounds.v3',
'musiclab.cover.global-karaoke-midi-exclusion.v1',
'musiclab.cover.original-performer-version-caps.v1',
'musiclab.cover.cross-upload-recording-dedupe.v1',
'musiclab.cover.best-covers-other-artists-breadth.v1',
];

test('LAB71 pins exact permanent user-approved behavior contract, no old active conflict',()=>{
 assert.equal(memory.canonicalBehaviorRevision,'2026-10-27-coverlab-foundational-immutable-title-global-exclusions-and-diversity-v3');
 assert.equal(memory.confirmedGoodBaseline.commit,'26f652fd34cbf6856d76c6efc8baf3fd14bf333f');
 for(const id of required) {
  const row=memory.rules.find(x=>x.id===id);
  assert.ok(row, id);
  assert.equal(row.status,'active',id);
  assert.equal(row.protected,true,id);
 }
 for(const id of ['musiclab.cover.work-identity-progressive.v2','musiclab.cover.source-and-category-rounds.v2'])
  assert.equal(memory.rules.find(x=>x.id===id).status,'retired');
 assert.equal(memory.foundationsImplementationState.startsWith('DOCUMENTED_NOT_IMPLEMENTED'),true);
});

test('One permanent identity gate before ANY round-specific confidence branch',()=>{
 const before=admission.indexOf('CoverLab71FoundationGate.matchesRequestedWork(');
 const after=admission.indexOf('val admissible = when');
 assert.ok(before>0 && before<after);
 assert.match(admission,/val containsTitle = exactTitle/);
 assert.doesNotMatch(admission,/val containsTitle = videoTitle\.contains\(targetTitle/);
 assert.match(gate,/TitleMeaningResolver\.matchesBaseTitle\(title, text, aliases\)/);
 assert.match(gate,/candidate\.workRelationConfirmed/);
 assert.match(gate,/candidate\.sources\.any/);
});

test('No karaoke/MIDI/backing track or tutorial in ANY tier or incoming source lane',()=>{
 for(const word of ['karaoke','midi','backing','base','minus','tutorial'])
  assert.match(gate,new RegExp(word));
 assert.match(gate,/internal fun isForbidden/);
 assert.match(gate,/fun matchesRequestedWork/);
 assert.doesNotMatch(plan, /karaoke singer cover/);
 assert.match(active,/CoverLab71FoundationGate\.isForbidden\(/);
});

test('Original studio1 live2 remix2 cap, per-recording dedupe across previous rounds',()=>{
 assert.match(gate,/OriginalType\.STUDIO -> 1/);
 assert.match(gate,/OriginalType\.LIVE -> 2/);
 assert.match(gate,/OriginalType\.REMIX -> 2/);
 assert.match(gate,/OriginalType\.DUET -> Int\.MAX_VALUE/);
 assert.match(gate,/if \(originalArtist\.isBlank\(\) \|\|/);
 assert.match(gate,/CoverLabTurboTwinGuard\.sameRecording\(previous, seed\)/);
 assert.match(active,/val priorEntries = previousRoundRows \+ publishedOriginalSnapshots \+/);
 assert.match(active,/CoverLab71FoundationGate\.isDuplicate\(seed, priorEntries\)/);
 assert.match(active,/CoverLab71FoundationGate\.withinOriginalCaps\(/);
 assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
});

test('Real singer cover discovery is early, without changing LAB66 Youtube thumbnails',()=>{
 assert.match(plan,/Query\("\$clean \$artist cover", AiCoverCategory\.COVER\)/);
 assert.match(plan,/professional singer cover/);
 assert.match(plan,/studio cover by other singers/);
 assert.match(admission,/LAB71: properly attributed real alternate-singer covers/);
 assert.match(active,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
 assert.match(active,/tryPublish\(candidate, searchedVideo = video\)/);
 assert.match(active,/session\.playReadyVideoIds\.add\(id\)/);
 assert.match(b,/i\.ytimg\.com\/vi\/\$\{video\.id\}\/hqdefault\.jpg/);
});

test('No native audio, Cloud D1 mutation, package/signing or UI mutation in live app region',()=>{
 assert.doesNotMatch(active,/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
 assert.match(active,/backgroundWorkBlocked\(\)/);
 assert.match(active,/isCoverAudioAtRisk\(\)/);
});
