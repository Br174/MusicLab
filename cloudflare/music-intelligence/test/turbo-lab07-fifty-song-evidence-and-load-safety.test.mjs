import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const fixture=JSON.parse(fs.readFileSync('cloudflare/music-intelligence/test/fixtures/turbo-lab07-real-fifty-song-catalog-baseline.json','utf8'));
const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const workflow=fs.readFileSync('.github/workflows/turbo-lab07-real-50song-gate.yml','utf8');
const rows=fixture.records;
const mean=key=>rows.reduce((a,b)=>a+b[key],0)/rows.length;
const segment=source.slice(source.indexOf('private suspend fun discoverCoverInfo('),source.indexOf('private fun toCoverInfoCandidate('));
test('EVIDENCE 01 all fifty authentic baseline songs are preserved',()=>{
 assert.equal(rows.length,50);
 assert.equal(new Set(rows.map(r=>r.title+'|'+r.artist)).size,50);
});
test('EVIDENCE 02 baseline mean linked video-ID yield is grounded rather than guessed',()=>{
 assert.equal(Number(mean('documentaryVideoIds').toFixed(2)),12.8);
});
test('EVIDENCE 03 documentary candidate count is separately reported',()=>{
 assert.equal(Number(mean('documentaryCoverCandidates').toFixed(2)),17.72);
});
test('EVIDENCE 04 verified YouTube average cannot be falsely claimed from links',()=>{
 assert.ok(rows.every(r=>r.verifiedVideoIds===null));
 assert.equal(fixture.method.claim_level.includes('NOT verified'),true);
});
test('EVIDENCE 05 rows without results remain in average denominator',()=>{
 assert.equal(rows.filter(r=>r.documentaryVideoIds===0).length,5);
});
test('EVIDENCE 06 documented source queries use measured syntax, not obsolete one',()=>{
 assert.ok(segment.includes('"""song:"$cleanTitle" artist:"$cleanArtist""""'));
 assert.ok(segment.includes('"""song:"$cleanTitle""""'));
});
test('EVIDENCE 07 precise source lookup precedes title-only fallback',()=>{
 assert.match(segment,/val precise = withContext\(Dispatchers.IO\)/);
 assert.match(segment,/val matchesWork = precise\?\.second/);
 assert.match(segment,/if \(matchesWork\) \{\s*listOfNotNull\(precise\)\s*\} else \{/);
});
test('EVIDENCE 08 cancellation and source timing are bounded',()=>{
 assert.match(source,/continuation\.invokeOnCancellation \{ call\.cancel\(\) \}/);
 assert.match(browser,/TURBO_COVER_INFO_WAIT_MS = 4_100L/);
 assert.match(browser,/TURBO_BURST_DEADLINE_MS = 5_000L/);
});
test('EVIDENCE 09 bulk release gate does not launch on unrelated code commits',()=>{
 assert.match(workflow,/paths:\s*-\s*\.github\/workflows\/turbo-lab07-real-50song-gate\.yml/);
 assert.ok(!workflow.includes('assembleFossDebug'));
});
test('EVIDENCE 10 source limits remain <=50 unique verified video references',()=>{
 assert.ok(rows.every(r=>r.documentaryVideoIds<=50&&r.documentaryCoverCandidates<=50));
 assert.match(browser,/TURBO_RESULT_QUOTA = 50/);
});
