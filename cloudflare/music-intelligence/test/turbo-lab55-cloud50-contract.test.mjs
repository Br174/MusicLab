import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const browser=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const engine=readFileSync(root+'CoverLab55VisibleCandidates.kt','utf8');
const cloud=readFileSync(root+'CloudMusicDiscovery.kt','utf8');

test('LAB55 first round keeps Cloud author credits rather than stripping them',()=>{
 assert.doesNotMatch(browser,/if \(!appendVerifiedRound\) seed else seed\.copy\(/);
 assert.doesNotMatch(browser,/if \(coverRoundNumber == 1\) baseSeed else baseSeed\.copy\(/);
 assert.match(browser,/val seed = baseSeed\.copy\(/);
 assert.match(browser,/item\.composers\.forEach/);
 assert.match(browser,/candidate\.composers\.forEach/);
});
test('LAB55 does not throw real Cloud archives away when 10 seconds elapsed',()=>{
 assert.match(browser,/CloudMusicDiscovery\.searchArchive\([\s\S]*?limit = 100/);
 assert.match(browser,/CoverLab55VisibleCandidates\.cloudArchiveSeeds\(/);
 assert.match(browser,/CloudMusicDiscovery\.discoverMemoryState\(/);
 assert.match(browser,/appendCatalogEvidence\(archiveSeeds\)/);
 assert.match(browser,/appendCatalogEvidence\(memorySeeds\)/);
});
test('LAB55 supports 50 real catalog results pending certification, never synthetic rows',()=>{
 assert.match(engine,/const val QUOTA = 50/);
 assert.match(engine,/if \(seed\.sourceNames\.isEmpty\(\)\) return false/);
 assert.match(engine,/TitleMeaningResolver\.matchesBaseTitle\(songTitle, seed\.trackTitle\)/);
 assert.match(engine,/DiscogsVersionSource\.recordingIdentityKey\(seed\)/);
 // LAB64: documentary records may be visible while playback-video verification remains independent.
 assert.match(engine,/val videoVerified = rawVideo\.matches\(Regex\(/);
 assert.match(engine,/verifiedVideoIds == null \|\| rawVideo in verifiedVideoIds/);
 assert.match(engine,/val videoVerified = rawVideo\.matches\(Regex\(/);
 assert.match(browser,/publishedCoverSnapshots = CoverLab55VisibleCandidates\.publish/);
 assert.match(browser,/val initialCovers = CoverLab55VisibleCandidates\.publish/);
});
test('LAB55 labels pending candidates and does not assign verified scores',()=>{
 assert.match(browser,/provisional = isTurbo &&/);
 assert.match(browser,/Da verificare · registrazione candidata/);
 assert.match(browser,/showConfidence = category == DirectVersionCategory\.ALL &&/);
 assert.match(engine,/CoverSameWorkGate\.hasWorkIdentity/);
});
test('LAB55 keeps app identity and read-only Cloud gateway unchanged',()=>{
 assert.match(cloud,/internal val turboReadOnly/);
 assert.match(cloud,/trackArchive = true/);
 assert.match(readFileSync(root+'CoverRoundSearchPlan.kt','utf8'),
   /if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
});
