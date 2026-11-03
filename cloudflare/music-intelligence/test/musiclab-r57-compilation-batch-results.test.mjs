import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const root = 'app/src/main/kotlin/com/metrolist/music/';
const ui = read(root + 'ui/component/CompilationScreen.kt');
const client = read(root + 'discogs/DiscogsClient.kt');
const light = read(root + 'ui/component/CompilationLightSearchPolicy.kt');
const search = ui.slice(ui.indexOf('fun runSearch('), ui.indexOf('LaunchedEffect(trackRequest?.requestId', ui.indexOf('fun runSearch(')));
const page = ui.slice(ui.indexOf('suspend fun loadPage('), ui.indexOf('fun searchIdentity('));

test('R57: filters-only search does not generate an empty indexed track request', () => {
  assert.match(search, /trackTitle = query\.takeIf\(String::isNotBlank\)\?\.let/);
  assert.match(search, /else activeSearch\?\.takeIf \{ it\.query == entered \}\?\.trackTitle/);
  assert.match(search, /\}\?\.takeIf\(String::isNotBlank\)/);
  assert.doesNotMatch(search, /sourceTitle\.ifBlank \{ query \}/);
  assert.match(client, /query\.trim\(\)\.takeIf\(String::isNotBlank\)\?\.let \{ addQueryParameter\("q", it\) \}/);
  assert.match(client, /if \(!trackTitle\.isNullOrBlank\(\)\)/);
});

test('R57: all Discogs candidates are committed atomically before background verification', () => {
  assert.match(page, /val visibleItems = if \(criteria\.trackTitle != null\)\s*pageResult\.items/);
  assert.match(page, /results = if \(replaceResults\) \{\s*visibleItems/);
  assert.match(page, /perPage = if \(criteria\.language == null\) 100 else 25/);
  assert.ok(page.indexOf('results = if (replaceResults)') < page.indexOf('verificationJob = scope.launch'));
  assert.ok(page.indexOf('CompilationSessionStore.results = results') < page.indexOf('verificationJob = scope.launch'));
  assert.match(page, /verifyIndexedTrackCandidates\(pageResult\.items, criteria, token, generation\) \{ confirmed ->/);
  assert.doesNotMatch(page, /results = \(results \+ confirmed\)/);
});

test('R57: checking a tracklist never silently claims all candidates are verified', () => {
  assert.match(ui, /verifiedTrackIds\[confirmed\.id\] = true/);
  assert.match(ui, /if \(verifiedTrackIds\[candidate\.id\] == null\) verifiedTrackIds\[candidate\.id\] = false/);
  assert.match(ui, /"Da verificare nella tracklist"/);
  assert.match(ui, /"Brano non confermato"/);
  assert.match(ui, /"Brano confermato nella tracklist"/);
  assert.match(ui, /"Visualizzati \$\{results\.size\} dischi/);
  assert.match(ui, /verificationJob\?\.cancel\(\)/);
  assert.match(ui, /if \(generation != searchGeneration\) return false/);
  assert.match(ui, /isPlaybackPriorityBurstActive\(\)/);
});

test('R57: the chosen source and existing player logic are preserved', () => {
  assert.match(light, /TITLE_ARTIST\("Titolo \+ cantante"\)/);
  assert.match(light, /TITLE\("Solo titolo"\)/);
  assert.match(light, /COVER_LITE\("Cover"\)/);
  assert.match(ui, /mode = mode\.toggle\(category\)/);
  assert.match(ui, /MusicLabCompilationSnapshot\.save/);
  assert.match(ui, /selected = found\.song\.toMediaItem\(\)/);
  assert.match(ui, /resolveTrack\(trackIndex, current\)\?\.song\?\.toMediaItem\(\)/);
});

test('R57: lightweight detail cache is distinct from complete Discogs release videos', () => {
  assert.match(client, /lightweightTracklistCache\[releaseId\]\?\.let/);
  assert.match(client, /if \(includeMasterVideos\) \{\s*detailCache\[releaseId\] = detail/);
  assert.match(client, /lightweightTracklistCache\[releaseId\] = detail/);
  assert.match(client, /lightweightTracklistCache\.size >= 200/);
  assert.match(client, /DISCOGS_MIN_REQUEST_INTERVAL_MS = 1_050L/);
});
