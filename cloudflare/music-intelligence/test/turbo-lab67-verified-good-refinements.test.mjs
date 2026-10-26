import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const browser=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const coverinfo=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const twins=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboTwinGuard.kt','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
test('Protect LAB66 user-device verified known-good video-first method',()=>{
 assert.equal(memory.confirmedGoodBaseline.commit,'26f652fd34cbf6856d76c6efc8baf3fd14bf333f');
 assert.match(browser,/LAB66 canonical Behavior Memory/);
 assert.match(browser,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
 assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
 assert.match(browser,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
 assert.match(browser,/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg/);
});
test('COVER.INFO artist-specific first hit never suppresses title-wide variants',()=>{
 const region=coverinfo.slice(coverinfo.indexOf('private suspend fun discoverCoverInfo('),coverinfo.indexOf('private fun toCoverInfoCandidate('));
 assert.match(region,/val fallback = searchTerms\.getOrNull\(1\)/);
 assert.doesNotMatch(region,/if \(matchesWork && !deepRecovery\)/);
 assert.match(region,/listOfNotNull\(precise, fallback\)/);
 assert.match(region,/onEarlyVideoCandidates\(partial\)/);
 assert.match(browser,/diagnostic\.name == "MusicBrainz"/);
 assert.match(browser,/val linked = lead\.copy\(/);
});
test('Orange stays available until untried queries are truly exhausted',()=>{
 assert.match(browser,/session\.roundEmptyPasses\+\+/);
 assert.match(browser,/session\.roundEmptyPasses = 0/);
 assert.doesNotMatch(browser,/session\.roundEmptyPasses >= 2/);
 assert.match(browser,/session\.youtubeQueryCursor = cursor \+ offset \+ 1/);
 assert.match(browser,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
});
test('Video IDs, normalized twin recordings and responsive player remain protected',()=>{
 assert.match(browser,/earlyVideoIds\.add\(id\)/);
 assert.match(browser,/CoverLabTurboTwinGuard\.sameRecording\(it, seed\)/);
 assert.match(browser,/withContext\(Dispatchers\.Default\)/);
 assert.match(browser,/backgroundWorkBlocked\(\)/);
 assert.match(browser,/isCoverAudioAtRisk\(\)/);
 assert.match(twins,/left\.kind != right\.kind/);
 assert.match(twins,/left\.durationSeconds/);
 assert.doesNotMatch(browser.slice(browser.indexOf('LAB66 canonical Behavior Memory'),browser.indexOf('// A further Cover round never clears')),/DELETE FROM|DROP TABLE/);
});
