import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read=p=>fs.readFileSync(p,'utf8');
const base='app/src/main/kotlin/com/metrolist/music/';
const ui=read(base+'ui/component/CompilationScreen.kt');
const api=read(base+'discogs/DiscogsClient.kt');
const policy=read(base+'ui/component/CompilationLightSearchPolicy.kt');
const snap=read(base+'ui/component/MusicLabCompilationSnapshot.kt');
const history=read(base+'ui/component/CompilationSearchSessionPolicy.kt');
const player=read(base+'playback/CoverPlaybackMemory.kt');
const menu=read(base+'ui/menu/MusicLabIntelligenceMenu.kt');

test('R54 exact three modes and automatic locandina-first singer+title no YouTube fallback',()=>{
 const nav=ui.slice(ui.indexOf('LaunchedEffect(trackRequest?.requestId'),ui.indexOf('fun loadNextPage('));
 assert.match(nav,/sourceTitle = target.title/);
 assert.match(nav,/sourceArtist = target.artist/);
 assert.match(nav,/lookupMode = CompilationLookupMode.TITLE_ARTIST/);
 assert.match(nav,/runSearch\(CompilationSearchCriteria\(/);
 assert.match(nav,/trackTitle = title/);
 assert.match(menu,/CompilationNavigationBridge.openForTrack\(/);
 assert.match(menu,/mediaMetadata.title/);
 assert.match(menu,/mediaMetadata.artists.firstOrNull\(\)\?\.name.orEmpty\(\)/);
 assert.match(policy,/TITLE_ARTIST\("Titolo \+ cantante"\)/);
 assert.match(policy,/TITLE\("Solo titolo"\)/);
 assert.match(policy,/COVER_LITE\("Cover"\)/);
 assert.doesNotMatch(nav,/CompilationFallbackQueryPolicy|videoTitle/);
});
test('R54 dropdown replaces dual buttons and runs user-selected isolated tracklist search',()=>{
 assert.match(ui,/CompilationLookupMode.entries.forEach/);
 assert.match(ui,/lookupMode = selected/);
 assert.match(ui,/trackTitle = title/);
 assert.match(ui,/runSearch\(CompilationSearchCriteria\(/);
 assert.doesNotMatch(ui,/Text\("Titolo"\)/);
 assert.doesNotMatch(ui,/onClick = \{ runSearch\(\) \}/);
});
test('R54 Discogs index track and release tracklist ONLY, never release-title matches',()=>{
 assert.match(api,/addQueryParameter\("track", trackTitle.trim\(\)\)/);
 const verify=ui.slice(ui.indexOf('suspend fun verifyIndexedTrackCandidates('),
  ui.indexOf('suspend fun loadPage('));
 assert.match(verify,/DiscogsClient.getRelease\(token, candidate.id,/);
 assert.match(verify,/release.tracks.none \{ track ->/);
 assert.match(verify,/CompilationLightSearchPolicy.accepts\(/);
 assert.doesNotMatch(verify,/release.title|candidate.title/);
});
test('R54 lightweight Cover accepts anonymous DJ/mashup variants, drops original artist',()=>{
 assert.match(policy,/editionMarker/);
 assert.match(policy,/COVER_LITE/);
 assert.match(policy,/originalArtistPresent\(track, originalArtist\)/);
 assert.match(policy,/named.isNotEmpty\(\) \|\| editionMarker.containsMatchIn\(track.title\)/);
 assert.doesNotMatch(policy,/SecondHandSongs|CoverSearchEngine|CoverNavigationBridge/);
});
test('R54 cover searches albums compilations and singles only through Discogs',()=>{
 assert.match(ui,/includeSingles = criteria.lookupMode == CompilationLookupMode.COVER_LITE/);
 assert.match(api,/if \(!includeSingles\) when \(mode\)/);
 assert.match(api,/if \(!includeSingles && !mode.accepts\(formats\)\) continue/);
});
test('R54 source title and chosen search strategy persist through app restore and history',()=>{
 assert.match(snap,/put\("lookupMode", saved.lookupMode.name\)/);
 assert.match(snap,/put\("originalTitle", saved.originalTitle\)/);
 assert.match(snap,/put\("originalArtist", saved.originalArtist\)/);
 assert.match(history,/put\("lookupMode", entry.lookupMode.name\)/);
 assert.match(ui,/lookupMode = restored.lookupMode/);
});
test('R54 previous R51 player and main Cover engine unaffected',()=>{
 assert.match(ui,/selected = found.song.toMediaItem\(\)/);
 assert.match(ui,/resolveTrack\(trackIndex, current\)\?\.song\?\.toMediaItem\(\)/);
 assert.doesNotMatch(player,/MusicLabCompilationCoverBridge.currentPlayerArtwork/);
 assert.doesNotMatch(menu,/CompilationLightSearchPolicy/);
});
