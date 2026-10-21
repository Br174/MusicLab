import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const compilation = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const nav = read('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt');
const screens = read('app/src/main/kotlin/com/metrolist/music/ui/screens/Screens.kt');
const mic = read('app/src/main/res/drawable/cover_microphone.xml');
const album = read('app/src/main/res/drawable/compilation_disc_search.xml');

test('LAB38 Compilation uses actual dynamic app theme palette', () => {
  assert.ok(compilation.includes('color = MaterialTheme.colorScheme.surfaceVariant'));
  assert.ok(compilation.includes('color = MaterialTheme.colorScheme.primary'));
  assert.ok(!compilation.includes('Color(0x'), 'Compilation palette must not be hard-coded');
});

test('LAB38 compilation search is filled, rounded and editable', () => {
  assert.ok(compilation.includes('BasicTextField('));
  assert.ok(compilation.includes('onValueChange = { query = it }'));
  assert.ok(compilation.includes('RoundedCornerShape(16.dp)'));
  assert.ok(compilation.includes('onClick = ::runSearch'));
  assert.ok(!compilation.includes('OutlinedTextField'));
});

test('LAB38 filter dropdowns preserve layout and state', () => {
  const f = compilation.split('private fun CompilationFilterDropdown(')[1].split('private val compilationGenres')[0];
  assert.ok(f.includes('Surface('));
  assert.ok(f.includes('.height(50.dp).clickable { expanded = true }'));
  assert.ok(f.includes('DropdownMenu('));
  assert.ok(f.includes('onSelect(option)'));
  for(const name of ['Anno','Genere','Stile','Nazione','Lingua']) {
    assert.ok(compilation.includes('label = "'+name+'"'),name);
  }
});

test('LAB38 menu opens on first launch and may be collapsed', () => {
  assert.ok(compilation.includes('var filtersExpanded: Boolean = true'));
  assert.ok(compilation.includes('filtersExpanded = !filtersExpanded'));
  assert.ok(compilation.includes('CompilationSessionStore.filtersExpanded = filtersExpanded'));
  assert.ok(compilation.includes('if (filtersExpanded)'));
});

test('LAB38 Cover and Compilation show labels beneath their icons', () => {
  assert.ok(nav.includes('label = if (!slimNav)'));
  assert.ok(!nav.includes('screen != Screens.Cover'));
  assert.ok(!nav.includes('screen != Screens.Compilation'));
  assert.ok(screens.includes('object Cover : Screens'));
  assert.ok(screens.includes('object Compilation : Screens'));
});

test('LAB38 both approved icons use the same 24dp frame and white stroke', () => {
  for(const xml of [mic,album]){
    assert.ok(xml.includes('android:width="24dp"'));
    assert.ok(xml.includes('android:height="24dp"'));
    assert.ok(xml.includes('android:strokeColor="@android:color/white"'));
    assert.ok(!xml.includes('#000000'));
  }
  // LAB39 supersedes LAB38's bright forced-white tint: default Material 3
  // NavigationBarItem local tint now applies identically to all icons.
  assert.ok(!nav.includes('background.luminance()'));
  assert.ok(!nav.includes('screen == Screens.Cover || screen == Screens.Compilation'));
});

test('LAB38 original microphone plus square album-play compilation icon', () => {
  assert.ok(mic.includes('M12,3.2C9.9,3.2'));
  assert.ok(album.includes('M6.2,3.6H17.8'));
  assert.ok(album.includes('M9.4,8.1L16,12L9.4,15.9Z'));
});

test('LAB38 Discogs query, audio and pagination are preserved', () => {
  for(const marker of [
    'DiscogsClient.searchCompilations', 'CompilationTrackResolver.resolveTrack',
    'playerConnection?.playQueue','paginationJob?.cancel()',
    'CompilationSessionStore.listIndex'
  ]) assert.ok(compilation.includes(marker),marker);
});
