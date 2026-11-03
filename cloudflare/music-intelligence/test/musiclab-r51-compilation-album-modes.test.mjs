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
  assert.match(ui,/label = "Categoria"/);
  assert.match(ui,/selected = mode\.label/);
  assert.match(ui,/DiscogsSearchMode\.entries\.map \{ it\.label \}/);
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
  assert.match(api,/DiscogsSearchMode\.BOTH -> Unit/);
  assert.match(api,/if \(!mode\.accepts\(formats\)\) continue/);
  assert.match(mode,/ALBUM -> isAlbum && !isCompilation/);
  assert.match(mode,/COMPILATION -> isCompilation/);
  assert.match(mode,/BOTH -> isAlbum \|\| isCompilation/);
  assert.match(mode,/\?: COMPILATION/);
});

test('R51: player verifies one category at a time; Album only after no Compilation matches', () => {
  const player=ui.slice(ui.indexOf('// R51 automatic player route:'),ui.indexOf('if (!succeeded && generation == searchGeneration)'));
  assert.match(player,/confirmPlayerCandidates\(/);
  assert.match(player,/verified\.isEmpty\(\)/);
  assert.match(player,/finalCriteria\.copy\(mode = DiscogsSearchMode\.ALBUM\)/);
  assert.match(player,/mode = finalCriteria\.mode/);
  assert.match(player,/results = verified\.distinctBy \{ it\.id \}/);
  assert.doesNotMatch(player,/DiscogsSearchMode\.BOTH/);
  assert.match(ui,/mode = DiscogsSearchMode\.COMPILATION\s*runSearch\(CompilationSearchCriteria/);
  assert.match(ui,/trackTitle = null, trackArtist = null/);
});

test('R51: mode participates in pagination, resume, state, history and source-safe search', () => {
  assert.match(ui,/mode = criteria\.mode/);
  assert.match(ui,/criteria\.mode\.name\)\.joinToString\("\|"\)/);
  assert.match(snap,/put\("mode", saved\.mode\.name\)/);
  assert.match(snap,/DiscogsSearchMode\.restored\(root\.optString\("mode"\)\)/);
  assert.match(history,/put\("mode", entry\.mode\.name\)/);
  assert.match(history,/mode = DiscogsSearchMode\.restored\(entry\.optString\("mode"\)\)/);
  assert.match(ui,/searchGeneration \+= 1/);
  assert.match(ui,/CompilationSessionStore\.activeSearch = finalCriteria/);
  assert.match(ui,/MusicLabSectionWorkCheckpoint\.standby/);
});

test('R51: original playback/detail and installed APK identity are not changed', () => {
  assert.match(ui,/MusicLabSectionPlayback\.select\(/);
  assert.match(ui,/CompilationTrackResolver\.resolveTrack\(/);
  assert.match(ui,/DiscogsClient\.getRelease\(/);
  assert.match(ui,/CompilationSearchHistoryStore\.load/);
  assert.match(ui,/MusicLabCompilationSnapshot\.save/);
});
