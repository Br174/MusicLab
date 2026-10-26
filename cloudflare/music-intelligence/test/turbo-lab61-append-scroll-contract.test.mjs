import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const ui=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const plan=readFileSync(root+'CoverRoundSearchPlan.kt','utf8');
const discovery=readFileSync(root+'CoverDiscoverySources.kt','utf8');
const publish=readFileSync(root+'CoverLab55VisibleCandidates.kt','utf8');

test('LAB61: Cerca ancora still uses LAB46 original rounds and VIDEO FIRST',()=>{
 assert.match(ui,/onClick = \{ runSearch\(forceRefresh = true, appendRound = true\) \}/);
 assert.match(ui,/val discogsRoundPage = if \(appendVerifiedRound\) coverRoundNumber \+ 1 else 1/);
 assert.match(ui,/coverRound = if \(appendVerifiedRound\) coverRoundNumber else 1/);
 assert.match(ui,/verifiedVideoIds = session\.playReadyVideoIds/);
 assert.match(publish,/verifiedVideoIds != null && video !in verifiedVideoIds/);
 for(const phrase of ['2 -> talent','3 -> amateur','else -> longTail','X Factor','The Voice','guitar cover','piano cover'])
   assert.ok(plan.includes(phrase),phrase);
 assert.ok(discovery.includes('discoverYouTubeCoverRound(cleanTitle, coverRound)'));
});
test('LAB61: previous verified rows are saved once and never erased by a repeated search',()=>{
 assert.match(ui,/val alreadyCommitted = publishedOriginalSnapshots \+ publishedCoverSnapshots/);
 assert.match(ui,/previousRoundRows = previousRoundRows \+ additional/);
 assert.match(ui,/CoverRoundStorage\.save\(context, sessionKey, savedRound, oldRowsSnapshot\)/);
 assert.match(ui,/if \(!appendVerifiedRound\) \{\s*session\.listIndex = 0\s*session\.listOffset = 0/);
 assert.match(ui,/if \(!appendVerifiedRound\) session\.originalWorkCredits = emptyList\(\)/);
 assert.match(ui,/previousRoundRows\s*\} else previousRoundRows\.filter/);
});
test('LAB61: visible video and counter remain cumulative, including while loading',()=>{
 assert.match(ui,/!loading \|\| previousRoundRows\.isNotEmpty\(\)/);
 assert.match(ui,/unifiedCoverDisplay \/\/ cumulative published VIDEO FIRST/);
 assert.match(ui,/results = if \(isTurbo && mode == DiscogsDirectMode\.COVER\)\s*unifiedCoverDisplay else results/);
 assert.match(ui,/"musiclab_lab61_append_progress"/);
 assert.match(ui,/"discogs_direct_turbo_\$\{seed\.fingerprint\}"/);
 assert.match(ui,/else if \(!appendRound && activeCriteria != null && activeCriteria != criteria\)/);
});
test('LAB61: suppress heavy sorting during append and preserve protected source modules',()=>{
 assert.match(ui,/sourceDiscoveryLoading && previousRoundRows\.isNotEmpty\(\)\s*\) emptyList\(\) else orderedResults\(results\)/);
 assert.match(ui,/val unifiedCoverDisplay = remember\(/);
 assert.match(ui,/preserveInputOrder = true/);
});
