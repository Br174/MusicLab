import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path,'utf8');
const comp = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const bridge = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt');
const policy = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt');
const api = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');

test('R46 Player -> Compilation delivers each tap, even on existing singleTop route', () => {
  assert.match(bridge, /AtomicLong/);
  assert.match(bridge, /MutableStateFlow<CompilationTrackRequest\?>/);
  assert.match(bridge, /serial.incrementAndGet\(\)/);
  assert.match(comp, /CompilationNavigationBridge.requests.collectAsStateWithLifecycle\(\)/);
  assert.match(comp, /LaunchedEffect\(trackRequest\?\.requestId\)/);
});
test('R53 locandina transfers original source label without launching legacy fallback', () => {
  const nav = comp.slice(comp.indexOf('LaunchedEffect(trackRequest?.requestId'),comp.indexOf('fun loadNextPage('));
  assert.match(nav, /query = target.title/);
  assert.match(nav, /mode = DiscogsSearchMode.COMPILATION/);
  assert.doesNotMatch(nav, /runSearch\(/);
  assert.doesNotMatch(nav, /CompilationFallbackQueryPolicy.query/);
  assert.match(policy, /fun songTitle\(fullVideoTitle: String, artist: String\)/);
  assert.match(comp, /trackTitle = cleaned/);
  assert.match(comp, /runSearch\(CompilationSearchCriteria\(/);
});

test('R53 verifies real tracklists only when Titolo is explicitly chosen', () => {
  assert.match(comp, /verifyIndexedTrackCandidates\(/);
  assert.match(comp, /CompilationTitleOnlyPolicy.selectedTrackIndex\(/);
  assert.match(comp, /trackTitle = criteria.trackTitle/);
  assert.doesNotMatch(comp, /val alternative = criteria.copy\(query = fallback\)/);
});

test('R46 original manual Compilation search retains R39 broad q mode', () => {
  const load = comp.slice(comp.indexOf('suspend fun loadPage('),comp.indexOf('fun runSearch('));
  assert.match(load, /query = criteria.query/);
  assert.match(load, /results = if \(replaceResults\) \{/);
  assert.doesNotMatch(load,/CompilationTrackMatchPolicy/);
  assert.match(api, /addQueryParameter\("format", "Compilation"\)/);
});
