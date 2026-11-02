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
test('R46 first query uses complete video title; fallback removes video noise and adds singer', () => {
  const nav = comp.slice(comp.indexOf('LaunchedEffect(trackRequest?.requestId'),comp.indexOf('fun loadNextPage('));
  assert.match(nav, /val fullVideoTitle = target.title.trim\(\)/);
  assert.match(nav, /query = fullVideoTitle/);
  assert.match(nav, /playerRequest = target/);
  assert.match(policy, /fun songTitle\(fullVideoTitle: String, artist: String\)/);
  assert.match(policy, /official/);
  assert.match(policy, /fun query\(fullVideoTitle: String, artist: String\)/);
});
test('R46 verifies real tracklist, so generic search results cannot prevent fallback', () => {
  assert.match(comp, /CompilationTrackMatchPolicy.matches/);
  assert.match(comp, /DiscogsClient.getRelease\(token, candidate.id,/);
  assert.match(comp, /if \(verified.isEmpty\(\) && fallback != null/);
  assert.match(comp, /criteria.copy\(query = fallback\)/);
  assert.match(comp, /totalPages = 1/);
});
test('R46 original manual Compilation search retains R39 broad q mode', () => {
  const load = comp.slice(comp.indexOf('suspend fun loadPage('),comp.indexOf('fun runSearch('));
  assert.match(load, /query = criteria.query/);
  assert.match(load, /results = if \(replaceResults\) \{/);
  assert.doesNotMatch(load,/CompilationTrackMatchPolicy/);
  assert.match(api, /addQueryParameter\("format", "Compilation"\)/);
});
