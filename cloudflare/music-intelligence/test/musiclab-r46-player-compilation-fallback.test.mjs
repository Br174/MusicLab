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
test('R54 player tap automatically searches indexed track from poster title and singer', () => {
  const nav = comp.slice(comp.indexOf('LaunchedEffect(trackRequest?.requestId'),comp.indexOf('fun loadNextPage('));
  assert.match(nav, /sourceTitle = target.title/);
  assert.match(nav, /sourceArtist = target.artist/);
  assert.match(nav, /runSearch\(CompilationSearchCriteria\(/);
  assert.match(nav, /trackTitle = title/);
  assert.doesNotMatch(nav, /CompilationFallbackQueryPolicy.query/);
  assert.match(comp, /CompilationLightSearchPolicy.accepts\(/);
  assert.match(comp, /verifyIndexedTrackCandidates\(/);
});

test('R46 original manual Compilation search retains R39 broad q mode', () => {
  const load = comp.slice(comp.indexOf('suspend fun loadPage('),comp.indexOf('fun runSearch('));
  assert.match(load, /query = criteria.query/);
  assert.match(load, /results = if \(replaceResults && criteria\.trackTitle == null\) \{/);
  assert.doesNotMatch(load,/CompilationTrackMatchPolicy/);
  assert.match(api, /addQueryParameter\("format", "Compilation"\)/);
});
