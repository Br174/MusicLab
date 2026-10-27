import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const b=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const admission=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const title=readFileSync(root+'CoverLab76BroadTitle.kt','utf8');
const hard=readFileSync(root+'CoverLab71FoundationGate.kt','utf8');
const plan=readFileSync(root+'CoverRoundSearchPlan.kt','utf8');

test('Button adds 3000 ms divided over up to two fresh YouTube queries',()=>{
 assert.match(b,/val manualExtraPerQueryMs = if \(appendRound && querySlice\.isNotEmpty\(\)\)/);
 assert.match(b,/3_000L \/ querySlice\.size/);
 assert.match(b,/withTimeoutOrNull\(5_500L \+ manualExtraPerQueryMs\)/);
 assert.match(b,/val querySlice = queries\.drop\(cursor\)\.take\(2\)/);
});

test('First zero-new-cover button step makes round complete and next click advances it',()=>{
 assert.match(b,/publishedCoverSnapshots\.size == visibleBefore/);
 assert.match(b,/querySlice\.isNotEmpty\(\) && publishedCoverSnapshots\.size == visibleBefore/);
 assert.match(b,/else if \(roundPlanComplete\) \{/);
 assert.match(b,/coverRoundNumber\+\+/);
 assert.match(b,/containerColor = if \(roundPlanComplete\) Color\(0xFF258E4E\)/);
 assert.match(b,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
});

test('Later rounds progressively accept exact named songs with descriptive additions',()=>{
 assert.match(admission,/allowExactTitleOnly = round >= 4/);
 assert.match(hard,/if \(allowExactTitleOnly\) return true/);
 assert.match(title,/qualifierOnly/);
 for(const token of ['"guitar"','"instrumental"','"coverband"','"stadium"',
  '"the"','"voice"','"sanremo"','"eurovision"','"remix"'])
  assert.ok(title.includes(token),token);
});

test('Different song title only allowed when documented foreign same work',()=>{
 assert.match(hard,/documentaryWork = candidate\.workRelationConfirmed/);
 assert.match(hard,/candidate\.category != AiCoverCategory\.FOREIGN/);
 assert.match(hard,/candidate\.category == AiCoverCategory\.FOREIGN/);
 assert.match(hard,/CoverLab76BroadTitle\.matches/);
 assert.match(hard,/isForbidden\(video, candidate\.title, candidate\.artist,/);
});

test('New multilingual + talent + stadium queries are additions not hard eligibility gates',()=>{
 for(const term of ['"Festival di Sanremo cover"','"Eurovision cover"',
 '"The Voice Global cover"','"famous singer covers"','"stadium concert cover"',
 '"guitar instrumental cover"','"stadio live"','"cover band"','"instrumental version"'])
  assert.ok(plan.includes(term),term);
 assert.doesNotMatch(b,/CoverLab72PublicationProof|firstRoundVideos|withDocumentedRelease/);
 assert.match(b,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
 assert.match(b,/CoverLab71FoundationGate\.withinOriginalCaps\(/);
 assert.match(b,/CoverLab71FoundationGate\.isDuplicate\(/);
});
