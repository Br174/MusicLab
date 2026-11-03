import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read = path => fs.readFileSync(path,'utf8');
const root='app/src/main/kotlin/com/metrolist/music/';
const ui=read(root+'ui/component/CompilationScreen.kt');
const api=read(root+'discogs/DiscogsClient.kt');
const kinds=read(root+'discogs/DiscogsSearchMode.kt');
const clean=read(root+'ui/component/CompilationTitleOnlyPolicy.kt');
const artwork=read(root+'ui/component/MusicLabCompilationCoverBridge.kt');
const player=read(root+'playback/CoverPlaybackMemory.kt');
const snap=read(root+'ui/component/MusicLabCompilationSnapshot.kt');
const hit=read(root+'ui/screens/search/MusicLabHitParadeDiscArtwork.kt');

test('R54: one selector, title+artist automatically, title-only and lightweight Cover explicitly', () => {
  assert.ok(ui.includes('CompilationLookupMode.entries.forEach'));
  assert.ok(ui.includes('lookupMode = CompilationLookupMode.TITLE_ARTIST'));
  assert.ok(ui.includes('sourceTitle = target.title'));
  assert.ok(ui.includes('sourceArtist = target.artist'));
  assert.ok(ui.includes('runSearch(CompilationSearchCriteria('));
  assert.ok(clean.includes('CompilationFallbackQueryPolicy.songTitle('));
});

test('R52: plain q and real Discogs track index are mutually exclusive', () => {
  assert.ok(api.includes('if (!trackTitle.isNullOrBlank())'));
  assert.ok(api.includes('addQueryParameter("track", trackTitle.trim())'));
  assert.ok(api.includes('addQueryParameter("q", it)'));
  assert.ok(ui.includes('trackTitle = criteria.trackTitle'));
  assert.ok(ui.includes('verifyIndexedTrackCandidates(pageResult.items, criteria, token, generation) { confirmed ->'));
});

test('R54 only tracklist rows can validate a hit, not a similar compilation name', () => {
  assert.ok(ui.includes('DiscogsClient.getRelease(token, candidate.id,'));
  assert.ok(ui.includes('release.formatDescriptions.ifEmpty { candidate.formats }'));
  assert.ok(ui.includes('CompilationLightSearchPolicy.accepts('));
  assert.ok(ui.includes('if (criteria.language != null'));
  assert.ok(kinds.includes('ALBUM -> isAlbum && !isCompilation'));
  assert.ok(kinds.includes('COMPILATION -> isCompilation'));
  assert.ok(ui.includes('Cerca nei dischi successivi'));
});

test('R54 initial search automatically uses locandina metadata and not video-specific text', () => {
  const nav=ui.slice(ui.indexOf('LaunchedEffect(trackRequest?.requestId'),ui.indexOf('fun loadNextPage('));
  assert.ok(nav.includes('sourceTitle = target.title'));
  assert.ok(nav.includes('sourceArtist = target.artist'));
  assert.ok(nav.includes('trackTitle = title'));
  assert.ok(nav.includes('runSearch(CompilationSearchCriteria('));
  assert.ok(!nav.includes('CompilationFallbackQueryPolicy.query'));
});

test('R52: opened release highlights and scrolls to corresponding track without playing it', () => {
  const open=ui.slice(ui.indexOf('fun openCompilation('),ui.indexOf('fun scheduleFullAudioLookup('));
  assert.ok(open.includes('CompilationLightSearchPolicy.accepts('));
  assert.ok(open.includes('CompilationSessionStore.selectedTrackIndex = selectedTrackIndex'));
  assert.ok(ui.includes('detailListState.scrollToItem(highlighted + 2)'));
  const detail=ui.slice(ui.indexOf('private fun CompilationDetailContent('));
  assert.ok(detail.includes('val selected = index == selectedTrackIndex'));
  assert.ok(!open.includes('playTrack(') && !open.includes('.playNow('));
});

test('R53: retain Discogs release list/detail covers but restore R51 player untouched', () => {
  assert.ok(ui.includes('MusicLabCompilationCoverBridge.remember(context, summary.id'));
  assert.ok(artwork.includes('getSharedPreferences(PREFS, Context.MODE_PRIVATE)'));
  assert.ok(player.includes('MusicLabHitParadeDiscArtwork.currentPlayerArtwork(context, metadata.id)'));
  assert.ok(!player.includes('MusicLabCompilationCoverBridge.currentPlayerArtwork'));
  assert.ok(!ui.includes('MusicLabCompilationCoverBridge.mediaItem('));
  assert.ok(ui.includes('selected = found.song.toMediaItem(),'));
  assert.ok(ui.includes('resolveTrack(trackIndex, current)?.song?.toMediaItem()'));
});

test('R52: user modes and prior R50 Hit Parade artwork stay intact', () => {
  assert.ok(ui.includes('DiscogsReleaseCategory.entries.forEach'));
  assert.ok(ui.includes('Text(mode.label'));
  assert.ok(ui.includes('CompilationUnifiedStyleFilter.options('));
  assert.ok(snap.includes('put("trackTitle", saved.trackTitle)'));
  assert.ok(snap.includes('put("trackArtist", saved.trackArtist)'));
  assert.ok(hit.includes('private suspend fun spotifyAlbum('));
  assert.ok(hit.includes('private suspend fun appleAlbum('));
  assert.ok(hit.includes('private suspend fun songDisc('));
});
