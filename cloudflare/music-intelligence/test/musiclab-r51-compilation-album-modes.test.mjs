import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p,'utf8');
const base='app/src/main/kotlin/com/metrolist/music/';
const ui=read(base+'ui/component/CompilationScreen.kt');
const api=read(base+'discogs/DiscogsClient.kt');
const mode=read(base+'discogs/DiscogsSearchMode.kt');
const style=read(base+'ui/component/CompilationUnifiedStyleFilter.kt');
const snap=read(base+'ui/component/MusicLabCompilationSnapshot.kt');
const history=read(base+'ui/component/CompilationSearchSessionPolicy.kt');

test('R51: manual category selector replaces Genere, old genre+style share Stile', () => {
  assert.match(ui,/DiscogsReleaseCategory.entries.forEach/);
  assert.match(ui,/if \(label.isBlank\(\)\) selected else/);
  assert.match(ui,/Text\(mode\.label/);
  assert.match(ui,/Checkbox\(/);
  assert.doesNotMatch(ui,/label = "Genere"/);
  assert.match(ui,/label = "Stile"/);
  assert.match(ui,/CompilationUnifiedStyleFilter\.options\(\s*compilationGenres, compilationStyles\)/);
  assert.match(ui,/val picked = CompilationUnifiedStyleFilter\.parse\(it\)/);
  assert.match(style,/GENRE = "Genere · "/);
  assert.match(style,/STYLE = "Stile · "/);
  assert.match(style,/fun parse\(option: String\): Pair<String\?, String\?>/);
});

test('R51: Discogs queries use correct format, Both only on manual selection', () => {
  assert.match(api,/when \(mode\) \{/);
  assert.match(api,/DiscogsSearchMode\.COMPILATION ->\s*addQueryParameter\("format", "Compilation"\)/);
  assert.match(api,/DiscogsSearchMode\.ALBUM ->\s*addQueryParameter\("format", "Album"\)/);
  assert.match(api,/DiscogsSearchMode\.BOTH, DiscogsSearchMode\.COMPILATION_SINGLE/);
  assert.match(api,/if \(!includeSingles && !mode\.accepts\(formats\)\) continue/);
  assert.match(mode,/ALBUM -> isAlbum && !isCompilation/);
  assert.match(mode,/COMPILATION -> isCompilation/);
  assert.match(mode,/BOTH -> isAlbum \|\| isCompilation/);
  assert.match(mode,/\?: COMPILATION/);
});

test('R54: three song strategies are independent of R51 album/compilation selectors', () => {
  const nav=ui.slice(ui.indexOf('LaunchedEffect(trackRequest?.requestId'),ui.indexOf('fun loadNextPage('));
  assert.match(nav,/mode = DiscogsSearchMode.COMPILATION/);
  assert.match(nav,/lookupMode = CompilationLookupMode.TITLE_ARTIST/);
  assert.match(nav,/runSearch\(CompilationSearchCriteria\(/);
  assert.match(ui,/lookupMode = selected/);
  assert.match(ui,/mode = mode,/);
  assert.doesNotMatch(ui,/finalCriteria\.copy\(mode = DiscogsSearchMode\.ALBUM\)/);
});

test('R51: mode participates in pagination, resume, state, history and source-safe search', () => {
  assert.match(ui,/mode = criteria\.mode/);
  assert.match(ui,/criteria\.mode\.name, criteria\.lookupMode\.name, criteria\.trackTitle\.orEmpty\(\)/);
  assert.match(ui,/criteria\.trackArtist\.orEmpty\(\)\)\.joinToString\("\|"\)/);
  assert.match(snap,/put\("mode", saved\.mode\.name\)/);
  assert.match(snap,/DiscogsSearchMode\.restored\(root\.optString\("mode"\)\)/);
  assert.match(history,/put\("mode", entry\.mode\.name\)/);
  assert.match(history,/mode = DiscogsSearchMode\.restored\(entry\.optString\("mode"\)\)/);
  assert.match(ui,/searchGeneration \+= 1/);
  assert.match(ui,/CompilationSessionStore\.activeSearch = criteria/);
  assert.match(ui,/MusicLabSectionWorkCheckpoint\.standby/);
});

test('R51: original playback/detail and installed APK identity are not changed', () => {
  assert.match(ui,/MusicLabSectionPlayback\.select\(/);
  assert.match(ui,/CompilationTrackResolver\.resolveTrack\(/);
  assert.match(ui,/DiscogsClient\.getRelease\(/);
  assert.match(ui,/CompilationSearchHistoryStore\.load/);
  assert.match(ui,/MusicLabCompilationSnapshot\.save/);
});
