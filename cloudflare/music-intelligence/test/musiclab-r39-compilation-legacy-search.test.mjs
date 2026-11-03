import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const comp = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt', 'utf8');
const policy = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt', 'utf8');
const api = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt', 'utf8');
const load = comp.slice(comp.indexOf('suspend fun loadPage('), comp.indexOf('fun runSearch('));
const nav = comp.slice(comp.indexOf('LaunchedEffect(trackRequest?.requestId'), comp.indexOf('fun loadNextPage('));

test('R39 restores wide R36 Discogs q-based search, 100 releases per page', () => {
  assert.match(load, /DiscogsClient\.searchCompilations\(/);
  assert.match(load, /query = criteria\.query,/);
  assert.match(load, /else if \(criteria\.language == null\) 100 else 25/);
  assert.match(load, /if \(criteria\.trackTitle != null\) 12/);
  assert.match(load, /trackTitle = criteria\.trackTitle/);
  assert.doesNotMatch(load, /verifyContainingTrack|CompilationTrackMatchPolicy/);
  assert.match(load, /results = if \(replaceResults\) \{\s*visibleItems/);
  assert.match(api, /DiscogsSearchMode\.COMPILATION ->\s*addQueryParameter\("format", "Compilation"\)/);
  assert.match(api, /DiscogsSearchMode\.ALBUM ->\s*addQueryParameter\("format", "Album"\)/);
  assert.match(api, /addQueryParameter\("q", it\)/);
});

test('menu transfers song title without activating the narrow song filter', () => {
  assert.match(bridge, /fun openForTrack\(title: String, artist: String\)/);
  assert.match(nav, /val fullVideoTitle = target\.title\.trim\(\)/);
  assert.match(nav, /query = fullVideoTitle/);
  assert.match(nav, /trackTitle = null, trackArtist = null/);
  assert.match(nav, /runSearch\(CompilationSearchCriteria\(/);
  assert.doesNotMatch(comp, /"Compilation verificate:/);
});

test('R38 safety, token, history and search isolation remain operational', () => {
  assert.match(comp, /searchGeneration \+= 1/);
  assert.match(comp, /if \(generation != searchGeneration\)/);
  assert.match(comp, /context\.dataStore\.data\.first\(\)\[DiscogsTokenKey\]/);
  assert.match(comp, /CompilationSearchHistoryStore\.save\(context, activeSearch \?: criteria\)/);
  assert.match(comp, /runSearch\(previous\)/);
  assert.match(policy, /private const val LIMIT = 30/);
  assert.match(policy, /musiclab_compilation_search_history_r38/);
});
