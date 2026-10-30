import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const path='app/src/main/kotlin/com/metrolist/music/ui/component/';
const browser=readFileSync(path+'DiscogsDirectVersionBrowser.kt','utf8');
const tags=readFileSync(path+'CoverFilterIdentity.kt','utf8');
test('R18 selection touches the in-memory displayed cover list only',()=>{
 const covers=browser.split('val visibleTrueCovers = remember(')[1]?.split('val visibleResults =')[0];
 assert.ok(covers,'cover display state');
 assert.match(covers,/turboStreamProofVersion, category/);
 assert.match(covers,/\.filter \{ directMatchesCategory\(it, category, isTurbo\) \}/);
 assert.match(covers,/\.take\(visibleLimit\)/);
 assert.ok(covers.indexOf('.filter { directMatchesCategory') < covers.indexOf('.take(visibleLimit)'));
 assert.match(browser,/category = selected\s+session.category = selected\s+scope.launch \{ listState.scrollToItem\(0\) \}/);
});
test('R18 filters reuse current documented source/YouTube title without provider work',()=>{
 assert.match(tags,/seed.sourceNames/);
 assert.match(tags,/seed.resolvedVideoTitle/);
 assert.match(tags,/seed.discogsVerificationChecked/);
 assert.match(tags,/cataloguedPublication\(seed\)/);
 assert.match(tags,/explicitStudio \|\|/);
 assert.doesNotMatch(tags,/https?:\/\/|suspend fun|withContext|Retrofit|DiscogsClient|YouTubeClient/);
 assert.match(browser,/val unifiedCoverDisplay = remember/);
 assert.match(browser,/val visibleResults =/);
});
test('R18 must leave new and old search admission, queue, video and sound alone',()=>{
 const source=readFileSync(path+'CoverLabTurboRoundAdmission.kt','utf8');
 const priority=readFileSync(path+'CoverPriorityOrder.kt','utf8');
 assert.match(source,/fun minimumRound\([\s\S]*?\): Int = 1/);
 assert.match(priority,/fun isPublished\(/);
 assert.match(browser,/CoverLabTurboRoundAdmission.evaluate\(candidate/);
 assert.match(browser,/CoverLab71FoundationGate.isForbidden/);
});
