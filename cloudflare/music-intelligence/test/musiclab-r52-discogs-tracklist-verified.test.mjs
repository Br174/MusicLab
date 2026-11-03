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

test('R52: player first tries verbatim full-q, then compilation track index, then album track index', () => {
  const a=ui.indexOf('// R51 automatic player route:');
  const b=ui.indexOf('if (!succeeded && generation == searchGeneration)',a);
  const chain=ui.slice(a,b);
  assert.ok(chain.includes('val alternative = criteria.copy(query = fallback)'));
  assert.ok(chain.includes('mode = DiscogsSearchMode.COMPILATION,'));
  assert.ok(chain.includes('trackTitle = songTitle'));
  assert.ok(chain.includes('finalCriteria.copy(mode = DiscogsSearchMode.ALBUM)'));
  assert.ok(chain.includes('results = verified.distinctBy { it.id }'));
  assert.ok(!chain.includes('DiscogsSearchMode.BOTH'));
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

test('R52: current Discogs cover takes priority over YouTube thumbnail in mini and expanded players', () => {
  assert.ok(ui.includes('MusicLabCompilationCoverBridge.remember(context, summary.id'));
  assert.ok(ui.includes('MusicLabCompilationCoverBridge.mediaItem('));
  assert.ok(artwork.includes('snapshot.source.startsWith("Compilation · ")'));
  assert.ok(artwork.includes('getSharedPreferences(PREFS, Context.MODE_PRIVATE)'));
  assert.ok(player.includes('MusicLabCompilationCoverBridge.currentPlayerArtwork(context, metadata.id)'));
  assert.ok(player.includes('MusicLabHitParadeDiscArtwork.currentPlayerArtwork(context, metadata.id)'));
  assert.ok(!artwork.includes('.prepare(') && !artwork.includes('.seekTo(') && !artwork.includes('.playNow('));
});

test('R52: user modes and prior R50 Hit Parade artwork stay intact', () => {
  assert.ok(ui.includes('label = "Categoria"'));
  assert.ok(ui.includes('CompilationUnifiedStyleFilter.options('));
  assert.ok(snap.includes('put("trackTitle", saved.trackTitle)'));
  assert.ok(snap.includes('put("trackArtist", saved.trackArtist)'));
  assert.ok(hit.includes('private suspend fun spotifyAlbum('));
  assert.ok(hit.includes('private suspend fun appleAlbum('));
  assert.ok(hit.includes('private suspend fun songDisc('));
});
