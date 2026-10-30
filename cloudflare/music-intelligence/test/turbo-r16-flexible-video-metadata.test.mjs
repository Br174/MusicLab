import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const dir='app/src/main/kotlin/com/metrolist/music/ui/component/';
const browser=readFileSync(dir+'DiscogsDirectVersionBrowser.kt','utf8');
const matcher=readFileSync(dir+'CoverR16FlexibleVideoMatch.kt','utf8');
const order=readFileSync(dir+'CoverPriorityOrder.kt','utf8');
test('video proof gate never runs before native queue',()=>{
 const publish=browser.split('suspend fun publish(candidate: CoverSourceCandidate) {')[1]?.split('fun resolveOriginalWhenUseful()')[0]||'';
 assert.ok(publish.includes('CoverR16FlexibleVideoMatch.plausible(candidate'));
 assert.ok(publish.includes('CoverR16FlexibleVideoMatch.evaluate(candidate'));
 assert.ok(!publish.includes('if (!preliminary.admitted)'));
 assert.ok(publish.includes('YouTube.queue(videoIds = ids)'));
});
test('mandatory title and two arbitrary independently verified dimensions',()=>{
 for(const name of ['title','performer','album','duration','documented-work','catalogue-track','work-writer'])
    assert.ok(matcher.includes('add("'+name+'")'),name);
 assert.ok(matcher.includes('minimumRound(evidence)'));
 assert.ok(!matcher.includes('categoryRound('));
});
test('catalogue provenance preserved; foreign interpretation document-gated',()=>{
 assert.ok(order.includes('durationSeconds = lead.durationSeconds'));
 assert.ok(order.includes('album = lead.album'));
 assert.ok(matcher.includes('row.category == AiCoverCategory.FOREIGN && documented(row)'));
});
test('catalogue-informed YouTube query variants remain bounded',()=>{
 assert.ok(browser.includes('CoverR16FlexibleVideoMatch.queries(next)'));
 assert.ok(matcher.includes('distinct().take(4)'));
});
