import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const d='app/src/main/kotlin/com/metrolist/music/ui/component/';
const p=readFileSync(d+'CoverPriorityOrder.kt','utf8');
const browser=readFileSync(d+'DiscogsDirectVersionBrowser.kt','utf8');
const round=readFileSync(d+'CoverRoundPresentation.kt','utf8');

test('R27 removes old unqualified YouTube video readiness fast pass',()=>{
 assert.match(p,/fun isIndependentYoutubeSource\(names: List<String>, hasVideoId: Boolean\)/);
 assert.match(p,/allowYoutubeFallback: Boolean = false/);
 assert.match(p,/allowYoutubeFallback \|\|[\s\S]{0,110}!isIndependentYoutubeSource\(row\.sources, hasVideoId = true\)/);
 assert.match(browser,/allowYoutubeFallback = SystemClock\.elapsedRealtime\(\) - preparationStartedAt >= 7_000L/);
 assert.doesNotMatch(p,/runnable\(id\) && !rows\.getValue\(id\)\.playbackVideoId\.isNullOrBlank\(\)/);
});
test('R27 first packet reserves only autonomous YouTube until existing 7s source window',()=>{
 assert.match(browser,/CoverPriorityOrder\.reserveYoutubeUntilFirstSourceWindow\(/);
 assert.match(p,/prepared\.sortedWith\(publicationComparator\)/);
 assert.match(p,/sourceWorkStillActive && elapsedMs < 7_000L/);
 assert.match(round,/clock\(\) - it >= 7_000L/);
 assert.match(browser,/appendVisible\(presentation\.takeTimedCycle\(\)\)/);
 assert.match(browser,/appendVisible\(presentation\.takeAutomatic\(\)\)/);
});
test('R27 no new exclusion, no player edit, R25 partial packets remain',()=>{
 assert.match(p,/fun eligible\([^]*?\): Boolean = true/);
 assert.match(round,/val overflow = linkedMapOf<String, T>\(\)/);
 assert.match(round,/const val INITIAL_SIZE = 10/);
 assert.match(round,/const val BLOCK_SIZE = 20/);
 assert.match(round,/const val ROUND_SIZE = 50/);
});
