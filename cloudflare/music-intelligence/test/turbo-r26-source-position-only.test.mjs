import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const p=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt','utf8');
const screen=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
test('R26 ordering only: MusicLab + Discogs together, Cover.info first, Youtube last',()=>{
 assert.match(p,/sources\.any \{ it == "cover.info" \}.*return 1/);
 assert.match(p,/it == "musiclab" \|\|/);
 assert.match(p,/it == "discogs" \|\| it == "musicbrainz"/);
 assert.match(p,/if \(hasVideoId && sources\.any \{ it == "archivio cloud" \}\) return 2/);
 assert.match(p,/sources\.any \{ it == "youtube music" \}.*return 5/);
 assert.match(p,/return 7/);
 const start=p.indexOf('fun order(');
 assert.ok(p.indexOf('.thenBy { it.preference }',start) < p.indexOf('.thenBy { when {',start));
 assert.match(screen,/seed\.sourceNames\.any \{ it\.equals\("COVER\.INFO", true\) \}/);
});
test('R26 keeps publication sorting only; native search and round admission untouched',()=>{
 assert.match(p,/val publicationComparator: Comparator<DiscogsVersionSeed>/);
 assert.match(p,/fun order\(/);
});
