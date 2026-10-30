import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const admission=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const priority=readFileSync(root+'CoverPriorityOrder.kt','utf8');
const browser=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
test('old evidence quota and inherited hold are gone',()=>{
  assert.doesNotMatch(admission,/evidence\.size\s*>=\s*[23]\s*->/);
  assert.match(admission,/fun minimumRound\([\s\S]*?\): Int = 1/);
  assert.doesNotMatch(priority,/heldUntilRound/);
  assert.doesNotMatch(priority,/\.thenBy\s*\{\s*it\.minimumRound\s*>/);
  assert.match(admission,/return Verdict\(sameWork,/);
});
test('native queue, exact title, video verification and player guards remain',()=>{
  assert.match(browser,/CoverLabTurboRoundAdmission\.evaluate\(candidate/);
  assert.match(browser,/CoverLab71FoundationGate\.isForbidden/);
  assert.match(browser,/CoverLabTurboTwinGuard\.possibleTwin/);
  assert.match(browser,/CoverPreparationPolicy\.metadataIds/);
  assert.match(priority,/candidate\.firstPublicationConfirmed -> 0/);
});
test('new behavior contract is active and old one retired',()=>{
  assert.equal(memory.rules.find(r=>r.id==='musiclab.cover.round-evidence-allocation.v1')?.status,'retired');
  assert.equal(memory.rules.find(r=>r.id==='musiclab.cover.quality-progressive.r17')?.status,'active');
  assert.equal(memory.approvedR17QualityProgressive.thresholds,'NONE');
});
