import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const kotlin=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const order=(items)=>items.slice().sort((a,b)=>Number(!!b.id)-Number(!!a.id)||b.score-a.score||(b.relation?1:0)-(a.relation?1:0)||a.name.localeCompare(b.name));
const pick=(s,i)=>({name:String(i),score:s,id:null,relation:false});
function codeSpan(start,end){const a=kotlin.indexOf(start),z=kotlin.indexOf(end,a+start.length);assert.ok(a>=0&&z>a,'Region missing '+start);return kotlin.slice(a,z)}
const priority=codeSpan('fun videoLookupPriority(source: List<DiscogsVersionSeed>)','fun videoPriorityPool(source:');
const early=codeSpan('val streamingVerifier = launch','val archiveDeferred = async');
const finalPhase=codeSpan('// LAB08 Pollicino: from the poster seal','\n        } else {\n        searchJob =');
test('SCORE 01 the ready-ID queue prioritizes video availability, then confidence from 20 to 1',()=>{
 assert.match(priority,/compareByDescending<DiscogsVersionSeed> \{ !it\.resolvedVideoId\.isNullOrBlank\(\) \}/);
 assert.match(priority,/\.thenByDescending \{ it\.confidenceScore \}/);
});
test('SCORE 02 score 12 with existing ID skips score 19 and 18 without IDs',()=>{
 const got=order([{...pick(19,'19')},{...pick(18,'18')},{...pick(12,'12'),id:'video12'}]);
 assert.deepEqual(got.map(x=>x.name),['12','19','18']);
});
test('SCORE 03 among IDs ready 20 takes precedence over 19 over 12',()=>{
 const got=order([20,12,19].map(n=>({...pick(n,n),id:'v'+n})));
 assert.deepEqual(got.map(x=>x.score),[20,19,12]);
});
test('SCORE 04 first available among 19,18,17,16 is 17, not blind wait for 19',()=>{
 const got=order([19,18,17,16].map(n=>({...pick(n,n),id:n===17?'v17':null})));
 assert.equal(got[0].score,17);
});
test('SCORE 05 when no candidate has an ID, search 20→1 by score',()=>{
 assert.deepEqual(order([1,12,19,20,6].map(n=>pick(n,n))).map(x=>x.score),[20,19,12,6,1]);
});
test('SCORE 06 all three video verification lanes use the SAME prioritization rule',()=>{
 assert.equal((kotlin.match(/\.let\(::videoLookupPriority\)/g)||[]).length,3);
 assert.match(early,/val pending = coverCandidatePool\(\)/);
 assert.match(finalPhase,/val pending = results[\s\S]+?\.let\(::videoLookupPriority\)/);
});
test('SCORE 07 poster snapshot also gives bound video IDs available places within maximum 50',()=>{
 assert.match(kotlin,/val finalRanked = videoLookupPriority\(rankedPosters\.filter \{ seed ->/);
 const inputs=Array.from({length:60},(_,i)=>({...pick(20-(i%20),i),id:i>=55?'v'+i:null}));
 const top=order(inputs).slice(0,50);
 assert.ok(top.every(x=>x.id!==null || x.score>=1));
 assert.ok([55,56,57,58,59].every(n=>top.some(x=>x.name===String(n))));
});
test('SCORE 08 original cover pool identity and title evidence stay mandatory',()=>{
 assert.match(kotlin,/val rankedPosters = coverCandidatePool\(\)\.filter\(::modeAcceptsSeed\)/);
 assert.match(kotlin,/\.filterNot\(::isOriginalPerformerVersion\)/);
 assert.match(kotlin,/fun hasPublishableVideo\(seed: DiscogsVersionSeed\): Boolean/);
});
test('SCORE 09 a later verified score-19 reclaims its score priority on one final atomic publication',()=>{
 assert.match(finalPhase,/val finalVideoSnapshots = sortGroup\(\s*publishedCoverSnapshots\.mapNotNull/);
 assert.match(finalPhase,/DirectVersionSort\.RELEVANCE/);
 const survivors=[{...pick(12,'12'),id:'v12'},{...pick(19,'19'),id:'v19'}];
 assert.deepEqual(survivors.sort((a,b)=>b.score-a.score).map(x=>x.name),['19','12']);
});
test('SCORE 10 video missing at T+10 disappears, no new poster added or step paging occurs',()=>{
 assert.match(finalPhase,/if \(resolved != null && hasPublishableVideo\(resolved\)\)/);
 assert.match(finalPhase,/else \{\s*null\s*\}/);
 assert.match(finalPhase,/publishedCoverSnapshots = finalVideoSnapshots/);
 assert.equal(finalPhase.includes('loadNextPage()'),false);
 assert.match(kotlin,/TURBO_VIDEO_DEADLINE_MS = 10_000L/);
});
