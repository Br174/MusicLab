import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read=p=>fs.readFileSync(p,'utf8');
const base='app/src/main/kotlin/com/metrolist/music/';
const ui=read(base+'ui/component/CompilationScreen.kt');
const title=read(base+'ui/component/CompilationTitleOnlyPolicy.kt');
const player=read(base+'playback/CoverPlaybackMemory.kt');
const hit=read(base+'ui/screens/search/MusicLabHitParadeDiscArtwork.kt');
const api=read(base+'discogs/DiscogsClient.kt');

test('R54 automatic first search comes only from locandina title and singer',()=>{
  const nav=ui.slice(ui.indexOf('LaunchedEffect(trackRequest?.requestId'),ui.indexOf('fun loadNextPage('));
  assert.match(nav,/sourceTitle = target.title/);
  assert.match(nav,/sourceArtist = target.artist/);
  assert.match(nav,/trackTitle = title/);
  assert.match(nav,/runSearch\(CompilationSearchCriteria\(/);
  assert.doesNotMatch(nav,/CompilationFallbackQueryPolicy.query|videoTitle/);
});

test('R54 exactly three indexed tracklist options replace both action buttons',()=>{
  assert.match(ui,/CompilationLookupMode.entries.forEach/);
  assert.match(ui,/CompilationLightSearchPolicy.indexTitle/);
  assert.match(ui,/lookupMode = selected/);
  assert.match(ui,/trackTitle = title/);
  assert.match(api,/addQueryParameter\("track", trackTitle.trim\(\)\)/);
  assert.doesNotMatch(ui,/Text\("Titolo"\)/);
  assert.doesNotMatch(ui,/onClick = \{ runSearch\(\) \}/);
});

test('R54 retains separate Compilation Album Entrambi filter and short label',()=>{
  assert.match(ui,/label = "",\s*selected = mode.label/);
  assert.match(ui,/DiscogsSearchMode.entries.map \{ it.label \}/);
  assert.match(ui,/lookupMode = CompilationLookupMode.TITLE_ARTIST/);
});

test('R53 restored player uses original native selected and neighbour queue with no custom cover bridge',()=>{
 assert.match(ui,/selected = found\.song\.toMediaItem\(\)/);
 assert.match(ui,/resolveTrack\(trackIndex, current\)\?\.song\?\.toMediaItem\(\)/);
 assert.match(ui,/MusicLabSectionPlayback\.select\(/);
 assert.doesNotMatch(ui,/MusicLabCompilationCoverBridge\.mediaItem\(/);
 assert.doesNotMatch(player,/MusicLabCompilationCoverBridge\.currentPlayerArtwork/);
 assert.match(player,/MusicLabHitParadeDiscArtwork\.currentPlayerArtwork/);
 assert.match(hit,/private suspend fun spotifyAlbum\(/);
});

test('R53 visual track selection still highlights verified disc row without audio autostart',()=>{
 const a=ui.indexOf('fun openCompilation('),b=ui.indexOf('fun scheduleFullAudioLookup(',a);
 const open=ui.slice(a,b);
 assert.match(open,/CompilationLightSearchPolicy\.accepts\(/);
 assert.doesNotMatch(open,/playTrack\(|\.playNow\(/);
 assert.match(ui,/detailListState\.scrollToItem\(highlighted \+ 2\)/);
});
