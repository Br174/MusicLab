import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {performance} from 'node:perf_hooks';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const sources = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');

test('LAB59 speed: local sorting never re-searches, clears the list or restarts video work', () => {
  const start = browser.indexOf('DirectSortSelector(');
  const end = browser.indexOf('if (headerExpanded)', start);
  assert.ok(start >= 0 && end > start);
  const change = browser.slice(start, end);
  assert.match(change, /sortMode = selected/);
  assert.match(change, /session\.sortMode = selected/);
  assert.doesNotMatch(change, /runSearch\(|loadPage\(|results = emptyList\(|publishedCoverSnapshots = emptyList\(|scheduleVideoPreload\(|rebuildStableOrder\(/);
});
test('LAB59 first results: work discovery is concurrent and COVER.INFO emits direct videos early', () => {
  assert.match(browser, /val firstPageDeferred = async/);
  assert.match(browser, /val memoryDeferred = async\(kotlinx\.coroutines\.Dispatchers\.IO\)/);
  assert.match(browser, /val externalDeferred =/);
  assert.match(browser, /onEarlyVideoCandidates = \{ early ->/);
  assert.match(browser, /results = mergePage\(results, videoSeeds, replace = false\)/);
  assert.match(sources, /onEarlyVideoCandidates: suspend \(List<CoverSourceCandidate>\) -> Unit/);
  assert.match(sources, /onEarlyVideoCandidates\(hit\.outcome\.candidates/);
  assert.match(sources, /val coverInfo = async\(Dispatchers\.IO\)/);
});
test('LAB59 quantity: identity-accepted versions remain even when historical year is uncertain', () => {
  assert.match(browser, /LAB59: the year is evidence for ranking/);
  assert.match(sources, /val chronologicallyPossible = identityAccepted/);
  assert.doesNotMatch(sources, /identityAccepted\.filter \{ candidate ->\s*val year = candidate\.year/);
  assert.match(sources, /score = score\.coerceAtMost\(6\)/);
});
test('LAB59 graphics: original and Cover video thumbnails appear independently', () => {
  assert.match(browser, /fun hasVideoPreview\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /fun hasPublishableVideo\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /val visibleOriginalVersions =/);
  assert.match(browser, /val visibleTrueCovers =/);
  assert.match(browser, /val group = ready\.take\(slots\)/);
  assert.doesNotMatch(browser, /if \(originals\.any \{ !isPlayReady\(it\) \}\) return/);
});
test('LAB59 playback: tap uses native player without an extra blocking stream preflight', () => {
  const a = browser.indexOf('suspend fun playResolvedContext(');
  const b = browser.indexOf('suspend fun loadPage(', a);
  const playback = browser.slice(a, b);
  assert.ok(a > 0 && b > a);
  assert.doesNotMatch(playback, /withTimeoutOrNull\(900L\)/);
  assert.match(playback, /connection\.playQueue\(/);
  assert.doesNotMatch(playback, /getStreamUrl\(/);
  assert.match(browser, /if \(playbackIsNormallyPlaying\(\)\) return/);
  assert.match(browser, /if \(backgroundWorkBlocked\(\) \|\| playbackIsNormallyPlaying\(\)\) break/);
  assert.match(browser, /heavy = !playbackIsNormallyPlaying\(\)/);
});
test('LAB59 cache: repeat searching same work preserves results and prepared video songs', () => {
  assert.match(browser, /val repeatSameWork = activeCriteria\?\.title\?\.equals\(criteria\.title, ignoreCase = true\) == true/);
  assert.match(browser, /results = retainedResults/);
  assert.match(browser, /if \(!repeatSameWork\) \{/);
  assert.match(browser, /session\.stableOrder = retainedOrder/);
});
test('LAB59 installer updates LAB58 in place rather than replacing the family', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="6501"/);
  assert.match(env, /UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});
test('local comparison can reorder hundreds of results without network or re-ranking', () => {
  const seeds = Array.from({length: 500}, (_,i) => ({id:i, year:i%19?1980+(i%45):null, score:20-(i%20)}));
  const start = performance.now();
  const oldest=[...seeds].sort((a,b)=>(a.year??99999)-(b.year??99999)||b.score-a.score);
  const newest=[...seeds].sort((a,b)=>(b.year??-99999)-(a.year??-99999)||b.score-a.score);
  assert.equal(oldest.length,500);
  assert.equal(newest.length,500);
  assert.equal(oldest[0].year,1980);
  assert.ok(oldest.at(-1).year === null);
  assert.ok(newest.at(-1).year === null);
  assert.ok(performance.now()-start<500, 'local 500-row sort took too long');
});

test('LAB59 out-of-order Cloud response cannot erase earlier Discogs or COVER.INFO results', () => {
  assert.match(browser, /LAB59: first Discogs page and direct COVER\.INFO videos run/);
  assert.match(browser, /current = results,\s*incoming = memoryVersions\.map\(::memoryCandidateToSeed\),\s*replace = false/);
  assert.match(browser, /sourceDiagnostics = listOf\(memoryDiagnostic\) \+/);
  assert.match(browser, /sourceDiagnostics\.filterNot \{ it\.name == "Archivio Cloud" \}/);
});
