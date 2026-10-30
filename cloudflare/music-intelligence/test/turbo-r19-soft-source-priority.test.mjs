import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const priority=readFileSync(root+'CoverPriorityOrder.kt','utf8');
const browser=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const cover=readFileSync(root+'CoverCardMetadata.kt','utf8');
const app=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const discovery=readFileSync(root+'CoverDiscoverySources.kt','utf8');

test('R19 source priority is soft, exact provenance and Youtube is last',()=>{
 assert.match(priority,/private fun musicalSourceTier\(names: List<String>, hasVideoId: Boolean\)/);
 assert.match(priority,/sources\.any \{ it == "cover.info" \}.*return 1/);
 assert.match(priority,/it == "discogs" \|\| it == "musicbrainz"/);
 assert.match(priority,/it == "archivio cloud"/);
 assert.match(priority,/sources\.any \{ it == "youtube music" \}.*return 5/);
 assert.match(priority,/it\.startsWith\("youtube discovery"\)/);
 assert.match(priority,/return 7/);
 assert.doesNotMatch(priority,/if\s*\(musicalSourceTier[\s\S]{0,50}return null/);
});
test('R19 modifies priority, not admission, rounds, known cover videos or Youtube',()=>{
 assert.match(priority,/candidate\.originalWorkReference/);
 assert.match(priority,/\.thenByDescending \{ it\.row\.collectorHave \?: 0 \}/);
 assert.match(browser,/val next = pending\.next\(rankTitle, rankOriginal, thisRound/);
 assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ visible/);
 assert.match(browser,/presentation\.prioritizePreparedWith\(CoverPriorityOrder\.publicationComparator\)/);
 assert.match(browser,/session\.knownVideoBindings/);
 assert.match(app,/fun minimumRound\([\s\S]*?\): Int = 1/);
 assert.match(discovery,/val coverInfo = async\(Dispatchers.IO\)/);
 assert.match(discovery,/val deepRound =/);
});
test('MusicLab existing archive is labeled only for verified cached posters',()=>{
 assert.match(cover,/seed\.videoResolutionChecked && !seed\.resolvedVideoId\.isNullOrBlank\(\)/);
 assert.match(cover,/"MusicLab \(archivio\)"/);
});
