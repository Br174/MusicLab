import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read = p => fs.readFileSync(p,'utf8');
const b='app/src/main/kotlin/com/metrolist/music/';
const ui=read(b+'ui/component/CompilationScreen.kt');
const policy=read(b+'ui/component/CompilationLightSearchPolicy.kt');
const api=read(b+'discogs/DiscogsClient.kt');
const saved=read(b+'ui/component/MusicLabCompilationSnapshot.kt');
const history=read(b+'ui/component/CompilationSearchSessionPolicy.kt');
const player=read(b+'playback/CoverPlaybackMemory.kt');
const menu=read(b+'ui/menu/MusicLabIntelligenceMenu.kt');

test('R54 only THREE options exist, no YouTube title or extra Cerca/Titolo actions',()=>{
 assert.match(policy,/TITLE_ARTIST\("Titolo \+ cantante"\)/);
 assert.match(policy,/TITLE\("Solo titolo"\)/);
 assert.match(policy,/COVER_LITE\("Cover"\)/);
 assert.doesNotMatch(policy,/YOUTUBE_VERSION|YOUTUBE_TITLE/);
 assert.match(ui,/CompilationLookupMode.entries.forEach \{ selected ->/);
 const a=ui.indexOf('BasicTextField('),b=ui.indexOf('historyOpen = true',a);
 const toolbar=ui.slice(a,b);
 assert.doesNotMatch(toolbar,/Text\("Titolo"\)|Text\("Cerca"\)/);
});

test('R54 first entry from player uses ONLY locandina title + singer and auto-starts',()=>{
 const a=ui.indexOf('LaunchedEffect(trackRequest?.requestId'),b=ui.indexOf('fun loadNextPage(',a);
 const nav=ui.slice(a,b);
 assert.match(nav,/sourceTitle = target.title/);
 assert.match(nav,/sourceArtist = target.artist/);
 assert.match(nav,/lookupMode = CompilationLookupMode.TITLE_ARTIST/);
 assert.match(nav,/val visible = listOf\(target.title, target.artist\)/);
 assert.match(nav,/runSearch\(CompilationSearchCriteria\(/);
 assert.match(nav,/trackTitle = title, trackArtist = target.artist.takeIf/);
 assert.doesNotMatch(nav,/youtubeTitle|videoTitle|CompilationFallbackQueryPolicy/);
 assert.match(menu,/CompilationNavigationBridge.openForTrack\(/);
 assert.match(menu,/mediaMetadata.title,/);
 assert.match(menu,/mediaMetadata.artists.firstOrNull\(\)\?\.name.orEmpty\(\)/);
});

test('R54 ALL modes verify actual Discogs tracklists and never accept matching release title',()=>{
 assert.match(ui,/verifyIndexedTrackCandidates\(pageResult.items, criteria, token, generation\)/);
 assert.match(ui,/DiscogsClient.getRelease\(token, candidate.id,/);
 assert.match(ui,/release.tracks.none \{ track ->/);
 assert.match(ui,/CompilationLightSearchPolicy.accepts\(/);
 assert.match(ui,/trackTitle = criteria.trackTitle/);
 assert.match(api,/addQueryParameter\("track", trackTitle.trim\(\)\)/);
 assert.match(policy,/only individual DiscogsTrack entries count/);
});

test('R54 Cover is intentionally light: uncredited remix or mashup works, original performer excluded',()=>{
 assert.match(policy,/COVER_LITE ->/);
 assert.match(policy,/originalArtistPresent\(track, originalArtist\)/);
 assert.match(policy,/alternateMarker.containsMatchIn\(track.title\)/);
 assert.match(policy,/mash/);
 assert.match(policy,/remix/);
 assert.match(policy,/named.isNotEmpty\(\)/);
 assert.doesNotMatch(ui,/CoverSearchEngine|SecondHandSongsCoverSource/);
});

test('R54 Cover searches Album Compilation and Singles without changing original category choices',()=>{
 assert.match(api,/includeSingles: Boolean = false/);
 assert.match(api,/if \(!includeSingles\) when \(mode\)/);
 assert.match(api,/if \(!includeSingles && !mode.accepts\(formats\)\) continue/);
 assert.match(ui,/includeSingles = criteria.lookupMode == CompilationLookupMode.COVER_LITE/);
 assert.match(ui,/criteria.lookupMode != CompilationLookupMode.COVER_LITE &&/);
 assert.match(ui,/DiscogsSearchMode.entries.map \{ it.label \}/);
});

test('R54 original singer and strategy survive screen navigation/restoration and history',()=>{
 assert.match(ui,/sourceTitle, sourceArtist, filtersExpanded/);
 assert.match(ui,/lookupMode = restored.lookupMode/);
 assert.match(saved,/put\("lookupMode", saved.lookupMode.name\)/);
 assert.match(saved,/put\("originalArtist", saved.originalArtist\)/);
 assert.match(history,/put\("lookupMode", entry.lookupMode.name\)/);
 assert.match(history,/lookupMode = CompilationLookupMode.restored/);
});

test('R54 player playback remains original verified R51, no YouTube audio mutation',()=>{
 assert.match(ui,/selected = found.song.toMediaItem\(\)/);
 assert.match(ui,/resolveTrack\(trackIndex, current\)\?\.song\?\.toMediaItem\(\)/);
 assert.doesNotMatch(player,/MusicLabCompilationCoverBridge.currentPlayerArtwork/);
 assert.doesNotMatch(ui,/MusicLabCompilationCoverBridge.mediaItem\(/);
});

test('R54 original song identity survives alternate version selection',()=>{
 assert.match(ui,/sourceArtist.takeIf\(String::isNotBlank\)/);
 assert.match(ui,/trackTitle = title,/);
 assert.match(ui,/lookupMode = selected/);
 assert.doesNotMatch(ui,/finalCriteria.copy\(mode = DiscogsSearchMode.ALBUM\)/);
});
