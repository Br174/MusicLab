import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const cycles=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt','utf8');
const browser=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const player=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const policy=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');
function fragment(start,end){
 const a=cycles.indexOf(start),b=cycles.indexOf(end,a+start.length);
 assert.ok(a>=0&&b>a,'Missing cycle fragment: '+start);
 return cycles.slice(a,b);
}

test('R25 publishes 3/7/12 verified rows after a cycle deadline, without a 20-card minimum',()=>{
 const ready=fragment('private fun ready(number: Int)','fun status(): Status');
 assert.match(ready,/state\.windowElapsed && !state\.windowPublished/);
 assert.match(ready,/partialCycleReady \|\| lateAfterThreeCycles/);
 const timed=fragment('fun takeTimedCycle(): List<T>','fun takeInitial(): List<T>');
 assert.match(timed,/minOf\(limit, state\.prepared\.size\)/);
 assert.match(timed,/completedBefore \+ 1/);
 assert.match(browser,/appendVisible\(presentation\.takeTimedCycle\(\)\)/);
});
test('R25 consumes exactly one numbered cycle per short packet, no premature green or loss',()=>{
 const cont=fragment('fun continueEmptyWindow(): Boolean','val preparationRound: Int');
 assert.match(cont,/if \(!current\.windowPublished\) current\.manualWindowSkips\+\+/);
 const status=fragment('fun status(): Status','fun needsPreparation(): Boolean');
 assert.match(status,/manualWindowSkips >= 3/);
 assert.match(status,/noUnpublished/);
 assert.match(status,/state\.shown\.size >= ROUND_SIZE/);
 const reveal=fragment('fun reveal(): List<T>','fun advance(): List<T>');
 assert.match(reveal,/return takeTimedCycle\(\)/);
 assert.match(reveal,/completedBefore \+ 1/);
 const stage=fragment('fun stage(number: Int, row: T): Boolean','fun total(number: Int)');
 assert.doesNotMatch(stage,/if \(state\.exhausted\) return false/);
 assert.match(stage,/number < visibleRound/);
 assert.match(stage,/round\\(maxOf\\(number, visibleRound\\)\\)/);
 assert.match(stage,/state\.overflow\[id\] = row/);
 const advance=fragment('fun advance(): List<T>','private fun ready(number: Int)');
 assert.match(advance,/previous\.prepared\.values \+ previous\.overflow\.values/);
});
test('R25 preserves Cover admission and R19 ordering and stage identity',()=>{
 const rules=readFileSync('behaviors/musiclab-cover.json','utf8');
 const r19=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt','utf8');
 assert.match(rules,/"approvedR17QualityProgressive"/);
 assert.match(r19,/it == "cover.info"/);
 assert.match(browser,/CoverLabTurboRoundAdmission\.evaluate\(/);
 assert.match(browser,/pending\.complete\(candidate\)/);
});
test('R25 safeguards audio recovery without changing identity, cache or Cover engine',()=>{
 assert.match(policy,/BUFFER_STALL_RECOVERY_DELAY_MS = 5_000L/);
 assert.match(policy,/MAX_BUFFER_STALL_RECOVERIES = 2/);
 assert.match(player,/retryReason = "R22 playback buffer stall"/);
 assert.match(player,/private fun applyQobuzEndpointOverrides\(/);
 assert.match(player,/cachedEnableSongCache/);
 assert.match(player,/PLAYBACK_START_BUFFER_MS = 900/);
 assert.match(player,/PLAYBACK_REBUFFER_MS = 6_000/);
});
