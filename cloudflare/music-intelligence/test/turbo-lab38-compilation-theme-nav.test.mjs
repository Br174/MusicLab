import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const compilation = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const nav = read('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt');
const screens = read('app/src/main/kotlin/com/metrolist/music/ui/screens/Screens.kt');
const mic = read('app/src/main/res/drawable/cover_microphone.xml');
const album = read('app/src/main/res/drawable/compilation_disc_search.xml');

test('LAB38 Compilation uses only real app theme colors, no hard-coded teal', () => {
  const part = compilation.split('fun CompilationScreen(')[1].split('@Composable\nprivate fun CompilationFilterDropdown(')[0];
  assert.match(part, /color = MaterialTheme\.colorScheme\.surfaceVariant/);
  assert.match(part, /color = MaterialTheme\.colorScheme\.primary/);
  assert.doesNotMatch(part, /Color\(0x|#[0-9A-Fa-f]{6}|strokeColor/);
});
test('LAB38 compilation search is filled, rounded, editable, with actual search action', () => {
  assert.match(compilation, /BasicTextField\(/);
  assert.match(compilation, /onValueChange = \{ query = it \}/);
  assert.match(compilation, /RoundedCornerShape\(16\.dp\)/);
  assert.match(compilation, /onClick = ::runSearch/);
  assert.doesNotMatch(compilation, /OutlinedTextField/);
});
test('LAB38 all five filters retain callback selection and menu content', () => {
  const f = compilation.split('private fun CompilationFilterDropdown(')[1].split('private val compilationGenres')[0];
  assert.match(f, /Surface\(/[\s\S]*?fillMaxWidth\(\)\.height\(50\.dp\)/);
  assert.match(f, /onClick = \{ expanded = true \}/);
  assert.match(f, /DropdownMenu\(/[\s\S]*?onSelect\(option\)/);
  assert.match(f, /"\$label: \$selected"/);
  for (const label of ['Anno','Genere','Stile','Nazione','Lingua']) {
    assert.ok(compilation.includes('label = "'+label+'"'));
  }
});
test('LAB38 compilation filters open by default but preserve manual collapse', () => {
  assert.match(compilation, /var filtersExpanded: Boolean = true/);
  assert.match(compilation, /filtersExpanded = !filtersExpanded/);
  assert.match(compilation, /CompilationSessionStore\.filtersExpanded = filtersExpanded/);
  assert.match(compilation, /if \(filtersExpanded\)/);
});
test('LAB38 Cover and Compilation labels shown next to original three nav labels', () => {
  assert.match(nav, /label = if \(!slimNav\)/);
  assert.doesNotMatch(nav, /screen != Screens\.Cover|screen != Screens\.Compilation/);
  assert.match(screens, /object Cover : Screens/);
  assert.match(screens, /object Compilation : Screens/);
});
test('LAB38 two new nav icons use same 24dp vector dimensions and clear white paths', () => {
  for (const xml of [mic, album]) {
    assert.match(xml, /android:width="24dp"/);
    assert.match(xml, /android:height="24dp"/);
    assert.match(xml, /android:strokeColor="@android:color\/white"/);
    assert.doesNotMatch(xml, /#000000/);
  }
  assert.match(nav, /MaterialTheme\.colorScheme\.background\.luminance\(\)/);
  assert.match(nav, /Color\.White/);
});
test('LAB38 exact approved Compilation icon is a square album with play triangle', () => {
  assert.match(album, /M6\.2,3\.6H17\.8/);
  assert.match(album, /M9\.4,8\.1L16,12L9\.4,15\.9Z/);
  assert.match(album, /M4\.7,7\.4L7\.5,4\.7/);
  assert.match(mic, /M12,3\.2C9\.9,3\.2/);
});
test('LAB38 music playback and Discogs pagination logic retained', () => {
  assert.match(compilation, /CompilationTrackResolver\.resolveTrack/);
  assert.match(compilation, /DiscogsClient\.searchCompilations/);
  assert.match(compilation, /playerConnection\?\.playQueue/);
  assert.match(compilation, /paginationJob\?\.cancel\(\)/);
  assert.match(compilation, /CompilationSessionStore\.listIndex/);
});
