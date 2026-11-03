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

test('R53 poster navigation presents exact full title once and NEVER triggers old auto search',()=>{
 const a=ui.indexOf('// R53: Player/locandina navigation ONLY');
 const b=ui.indexOf('fun loadNextPage()',a);
 assert.ok(a>=0&&b>a);
 const nav=ui.slice(a,b);
 assert.match(nav,/query = target\.title/);
 assert.match(nav,/mode = DiscogsSearchMode\.COMPILATION/);
 assert.doesNotMatch(nav,/runSearch\(/);
 assert.doesNotMatch(nav,/CompilationFallbackQueryPolicy\.query/);
 assert.doesNotMatch(ui,/val alternative = criteria\.copy\(query = fallback\)/);
 assert.doesNotMatch(ui,/finalCriteria\.copy\(mode = DiscogsSearchMode\.ALBUM\)/);
 assert.match(ui,/if \(trackRequest != null\) return@LaunchedEffect/);
});

test('R53 Cerca preserves ENTIRE user-visible string as q, with no cleanup or mutation',()=>{
 assert.match(ui,/onClick = \{ runSearch\(\) \}/);
 const a=ui.indexOf('fun runSearch('),b=ui.indexOf('val workKey = searchIdentity',a);
 const chosen=ui.slice(a,b);
 assert.match(chosen,/query = query\.trim\(\)/);
 assert.match(chosen,/trackTitle = null/);
 assert.match(chosen,/trackArtist = null/);
 assert.doesNotMatch(chosen,/CompilationFallbackQueryPolicy/);
 assert.match(api,/addQueryParameter\("q", it\)/);
});

test('R53 Titolo explicitly strips artist from poster label and searches verified tracklist',()=>{
 assert.match(ui,/Text\("Titolo"\)/);
 assert.match(ui,/CompilationTitleOnlyPolicy\.title\(\s*query, trackRequest\?\.artist\)/);
 assert.match(ui,/query = cleaned/);
 assert.match(ui,/trackTitle = cleaned/);
 assert.match(api,/addQueryParameter\("track", trackTitle\.trim\(\)\)/);
 assert.match(title,/matchesKnownArtist/);
 assert.match(title,/artist\.isBlank\(\) -> right/);
 assert.match(ui,/verifyIndexedTrackCandidates\(/);
 assert.match(ui,/CompilationTitleOnlyPolicy\.selectedTrackIndex\(/);
});

test('R53 category selector shows only Compilation or chosen mode without Categoria prefix',()=>{
 assert.match(ui,/label = "",\s*selected = mode\.label/);
 assert.match(ui,/if \(label\.isBlank\(\)\) selected else "\$label: \$selected"/);
 assert.match(ui,/DiscogsSearchMode\.entries\.map \{ it\.label \}/);
 assert.match(ui,/mode = DiscogsSearchMode\.COMPILATION/);
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
 assert.match(open,/selectedTrackIndex = CompilationTitleOnlyPolicy\.selectedTrackIndex\(/);
 assert.doesNotMatch(open,/playTrack\(|\.playNow\(/);
 assert.match(ui,/detailListState\.scrollToItem\(highlighted \+ 2\)/);
});
