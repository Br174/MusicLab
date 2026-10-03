import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const models = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const screen = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const uab = read('uab-project.env');

test('LAB29 exposes Discogs pagination metadata', () => {
  assert.match(models, /data class DiscogsCompilationPage/);
  assert.match(models, /val page: Int/);
  assert.match(models, /val pages: Int/);
  assert.match(models, /val totalItems: Int/);
  assert.match(client, /optJSONObject\("pagination"\)/);
  assert.match(client, /optInt\("items"\)/);
  assert.match(client, /optInt\("pages"\)/);
});

test('LAB29 requests large pages and preserves every Discogs release edition', () => {
  assert.match(client, /perPage: Int = 100/);
  assert.match(client, /perPage\.coerceIn\(1, 100\)/);
  assert.match(client, /distinctBy \{ it\.id \}/);
  assert.doesNotMatch(client, /summary\.masterId\?\.let/);
  assert.doesNotMatch(client, /formats\.none \{ it\.equals\("Compilation"/);
});

test('LAB29 freezes active search criteria across subsequent pages', () => {
  assert.match(screen, /data class CompilationSearchCriteria/);
  assert.match(screen, /var activeSearch by remember/);
  assert.match(screen, /suspend fun loadPage/);
  assert.match(screen, /page = currentPage \+ 1/);
});

test('LAB29 shows live loaded and total compilation counters', () => {
  assert.match(screen, /Compilation caricate: \$\{results\.size\} \/ Totale Discogs: \$totalDiscogsResults/);
  assert.match(screen, /Pagina \$currentPage di \$totalPages/);
  assert.match(screen, /Carico altri risultati… \$\{results\.size\}\/\$totalDiscogsResults/);
});

test('LAB29 loads the next Discogs page automatically at the result-list footer', () => {
  assert.match(screen, /item\(key = "discogs_load_more_/);
  assert.match(screen, /LaunchedEffect\(activeSearch, currentPage, totalPages\)/);
  assert.match(screen, /loadNextPage\(\)/);
  assert.match(screen, /currentPage < totalPages/);
});

test('LAB29 preserves the MusicLab language assist without corrupting Discogs total', () => {
  assert.match(screen, /filterByLanguage/);
  assert.match(screen, /Totale Discogs prima del filtro Lingua/);
  assert.match(screen, /totalDiscogsResults = pageResult\.totalItems/);
});

test('LAB29 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB29"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB29"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab29"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB29"/);
});
