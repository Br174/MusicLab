import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const k=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const env=fs.readFileSync('uab-project.env','utf8');
test('LAB09 ten-second poster burst publishes scored candidates',()=>{
 assert.match(k,/TURBO_BURST_DEADLINE_MS = 10_000L/);
 assert.match(k,/coverCandidatePool\(\)\.filter\(::modeAcceptsSeed\)/);
 assert.match(k,/publishedCoverSnapshots = finalRanked/);
});
test('LAB09 missing video leaves cover card and triggers cancellable repeated search',()=>{
 assert.ok(!k.includes('val finalVideoSnapshots ='));
 assert.match(k,/var manualVideoSearchJob by remember\(sessionKey\)/);
 assert.match(k,/while \(isActive && activeCriteria != null &&/);
 assert.match(k,/retryMissingVideo\(seed\)/);
});
test('LAB09 keeps app package and version 1009',()=>{
 assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1009"/);
});
