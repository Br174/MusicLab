import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const file=p=>fs.readFileSync(p,'utf8');
const plan=file('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt');
const sources=file('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const browser=file('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const points=file('app/src/main/kotlin/com/metrolist/music/ui/component/CoverQualityScore100.kt');
const storage=file('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt');
test('LAB46: first LAB45 round has no new YouTube discovery lane',()=>{
 assert.match(plan,/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
 assert.match(sources,/coverRound >= 2/);
 assert.match(browser,/coverRound = if \(appendVerifiedRound\) coverRoundNumber else 1/);
});
test('LAB46: later rounds rotate distinct talent, amateur and long-tail queries',()=>{
 for(const fragment of ['X Factor','The Voice','guitar cover','piano cover','rare cover',
 'foreign language adaptation','choir cover','regional language']){
   assert.ok(plan.includes(fragment), fragment);
 }
 assert.match(plan,/when \(round\)/);
 assert.match(plan,/2 -> talent/);
 assert.match(plan,/3 -> amateur/);
 assert.match(plan,/else -> longTail/);
 assert.match(plan,/\(round - 4\) \* 2/);
});
test('LAB46: YouTube candidates are NEVER automatically verified as same musical work',()=>{
 assert.match(sources,/YouTube\.SearchFilter\.FILTER_VIDEO/);
 assert.match(sources,/workRelationConfirmed = false/);
 assert.match(sources,/evidenceScore = 1/);
 assert.match(sources,/sameBaseTitle\(title, item\.title\)/);
 assert.match(sources,/withTimeoutOrNull\(5_000L\)/);
 assert.match(browser,/CoverSameWorkGate\.hasWorkIdentity/);
});
test('LAB46: /100 points separate from editorial approval, first50 unchanged',()=>{
 assert.match(browser,/if \(coverRoundNumber >= 2\)/);
 assert.match(browser,/CoverQualityScore100\.score\(seed\)/);
 assert.match(browser,/musiclabScore100 = seed\.musiclabScore100/);
 assert.match(browser,/roundPosition = previousRoundRows\.size \+ index \+ 1/);
 assert.match(browser,/seed\.confidenceScore\}\/20/);
 assert.match(browser,/seed\.musiclabScore100\}\/100/);
 assert.doesNotMatch(points,/manuallyApproved|APPROVED|REJECTED/);
});
test('LAB46: publication and restart retain unique per-video and per-recording positions',()=>{
 assert.match(storage,/put\("coverRound", seed\.coverRound\)/);
 assert.match(storage,/put\("roundPosition", seed\.roundPosition\)/);
 assert.match(storage,/DiscogsVersionSource\.recordingIdentityKey/);
 assert.match(browser,/previousRoundRows \+ publishedOriginalSnapshots \+ publishedCoverSnapshots/);
});

test('LAB46 selective restore: initial 50 use LAB45 Cloud memory and archive conversion only', () => {
 assert.match(browser, /val seed = baseSeed\.copy\(/);
 assert.match(browser, /if \(coverRoundNumber == 1\) \{[\s\S]*?DiscogsVersionSource\.markVideoResolved\(/);
 assert.match(browser, /item\.composers\.forEach/);
 assert.match(browser, /if \(!appendVerifiedRound\) \{\s*listOf\(it\.title\.lowercase\(\), it\.artist\.lowercase\(\), it\.category\.lowercase\(\)\)/);
 assert.match(browser, /CoverSameWorkGate\.hasWorkIdentity\(/);
 assert.match(browser, /fun sealPostersOnce\(\)/);
 assert.match(browser, /fun sealSecondPostersOnce\(\)/);
});
