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

test('R52: Cerca runs exact visible field; adjacent Titolo explicitly cleans and searches', () => {
  const start=ui.indexOf('BasicTextField(\n                                            value = query');
  const nearby=ui.slice(start,start+3800);
  assert.ok(nearby.includes('onClick = { runSearch() }'));
  assert.ok(nearby.includes('Text(if (loading) "…" else "Cerca")'));
  assert.ok(nearby.includes('Text("Titolo")'));
  assert.ok(nearby.includes('CompilationTitleOnlyPolicy.title('));
  assert.ok(nearby.includes('query = cleaned'));
  assert.ok(nearby.includes('trackTitle = cleaned'));
  assert.ok(nearby.includes('trackArtist = null'));
  assert.ok(clean.includes('CompilationFallbackQueryPolicy.songTitle('));
});

test('R52: plain q and real Discogs track index are mutually exclusive', () => {
  assert.ok(api.includes('if (!trackTitle.isNullOrBlank())'));
  assert.ok(api.includes('addQueryParameter("track", trackTitle.trim())'));
  assert.ok(api.includes('addQueryParameter("q", it)'));
  assert.ok(ui.includes('trackTitle = criteria.trackTitle'));
  assert.ok(ui.includes('verifyIndexedTrackCandidates(pageResult.items, criteria, token, generation)'));
});

test('R52: only verified actual tracklist matches are displayed for song searches', () => {
  assert.ok(ui.includes('DiscogsClient.getRelease(token, candidate.id,'));
  assert.ok(ui.includes('release.formatDescriptions.ifEmpty { candidate.formats }'));
  assert.ok(ui.includes('criteria.mode.accepts(formatLabels)'));
  assert.ok(ui.includes('CompilationTitleOnlyPolicy.selectedTrackIndex('));
  assert.ok(ui.includes('if (criteria.language != null'));
  assert.ok(kinds.includes('ALBUM -> isAlbum && !isCompilation'));
  assert.ok(kinds.includes('COMPILATION -> isCompilation'));
  assert.ok(ui.includes('Cerca nei dischi successivi'));
});

test('R53: poster is untouched until the user explicitly picks Cerca or Titolo', () => {
  const a=ui.indexOf('LaunchedEffect(trackRequest?.requestId');
  const b=ui.indexOf('fun loadNextPage(',a);
  const nav=ui.slice(a,b);
  assert.ok(nav.includes('query = target.title'));
  assert.ok(!nav.includes('runSearch('));
  assert.ok(!nav.includes('CompilationFallbackQueryPolicy'));
  assert.ok(!ui.includes('finalCriteria.copy(mode = DiscogsSearchMode.ALBUM)'));
  assert.ok(ui.includes('onClick = { runSearch() }'));
  assert.ok(ui.includes('Text("Titolo")'));
});

test('R52: opened release highlights and scrolls to corresponding track without playing it', () => {
  const open=ui.slice(ui.indexOf('fun openCompilation('),ui.indexOf('fun scheduleFullAudioLookup('));
  assert.ok(open.includes('selectedTrackIndex = CompilationTitleOnlyPolicy.selectedTrackIndex('));
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
  assert.ok(ui.includes('label = ""'));
  assert.ok(ui.includes('selected = mode.label'));
  assert.ok(ui.includes('CompilationUnifiedStyleFilter.options('));
  assert.ok(snap.includes('put("trackTitle", saved.trackTitle)'));
  assert.ok(snap.includes('put("trackArtist", saved.trackArtist)'));
  assert.ok(hit.includes('private suspend fun spotifyAlbum('));
  assert.ok(hit.includes('private suspend fun appleAlbum('));
  assert.ok(hit.includes('private suspend fun songDisc('));
});
