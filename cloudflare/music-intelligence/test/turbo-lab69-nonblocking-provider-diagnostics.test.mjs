import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const b=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const mem=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const active=b.slice(b.indexOf('// LAB66 canonical Behavior Memory'),b.indexOf('// A further Cover round never clears'));
test('keep LAB66 user-confirmed thumbnail engine as unmodified principle',()=>{
 assert.equal(mem.confirmedGoodBaseline.commit,'26f652fd34cbf6856d76c6efc8baf3fd14bf333f');
 assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
 assert.match(active,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
 assert.match(b,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
});
test('provider callback does not block on YouTube metadata validation',()=>{
 assert.match(active,/val sourceVideoGate = Semaphore\(2\)/);
 assert.match(active,/fun submitSourceVideo\(candidate: CoverSourceCandidate\)/);
 assert.match(active,/launch\(Dispatchers\.Main\.immediate\) \{\s*sourceVideoGate\.withPermit \{ tryPublish\(candidate\) \}/);
 assert.match(active,/onEarlyVideoCandidates = \{ candidates ->\s*candidates\.forEach\(::submitSourceVideo\)/);
 assert.match(active,/onSourceCompleted = \{ candidates, diagnostic, _ ->/);
 assert.match(active,/\) launch\(Dispatchers\.IO\) \{\s*\/\/ A separate bounded lane/);
});
test('real source availability is not declared while in progress; no duplicate LRCLIB',()=>{
 assert.match(active,/it\.name\.equals\(diagnostic\.name, ignoreCase = true\)/);
 assert.match(active,/CoverSourceDiagnostic\("LRCLIB", false, 0, "da controllare"\)/);
 assert.match(active,/note = "non completata: tempo limite o fonte non raggiungibile"/);
 assert.match(b,/diagnostic\.note\.contains\("in corso", ignoreCase = true\)/);
 assert.match(active,/thisRound > 1 -> "solo prima tornata"/);
});
test('direct YouTube transient failure retries once with audio protection',()=>{
 assert.match(active,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
 assert.match(active,/backgroundWorkBlocked\(\)/);
 assert.match(active,/withTimeoutOrNull\(2_000L\)/);
 assert.match(active,/session\.roundQueryRetries\+\+/);
});
test('no production cloud writes, native player or poster-fallback admission',()=>{
 assert.doesNotMatch(active,/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
 assert.match(active,/val sourceQueuedIds = ConcurrentHashMap\.newKeySet<String>\(\)/);
});
