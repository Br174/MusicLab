import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const hit = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt');
const comp = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const fast = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt');
const policy = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');

test('Hit Parade R36 uses one fast native song for artwork and subsequent playback', () => {
  assert.match(hit, /MusicLabFastHitArtwork\(MusicLabChartTrack\(/);
  assert.match(hit, /MusicLabFastPath\.find\(song\.title, song\.artist\)/);
  assert.match(hit, /myTap != tapSerial/);
  assert.match(hit, /MusicLabHistoricalHitParade\.load\(selected\)/);
  assert.match(hit, /\.rank h5/);
  assert.match(hit, /brano-dettagli-titolo/);
  assert.match(hit, /brano-dettagli-artista/);
});

test('FastPath reuses ordinary MusicLab search metadata with deduped bounded background lookups', () => {
  assert.match(fast, /YouTube\.searchSummary\(searchTerm, incognito = true\)/);
  assert.match(fast, /YouTube\.search\(searchTerm, YouTube\.SearchFilter\.FILTER_SONG\)/);
  assert.match(fast, /flights\.computeIfAbsent/);
  assert.match(fast, /Semaphore\(MusicLabFastPathPolicy\.SEARCH_PARALLELISM\)/);
  assert.match(fast, /MusicLabChartArtworkResolver\.sameRecordingLabel/);
  assert.match(fast, /MusicLabChartArtwork\(track\)/);
  assert.match(fast, /MusicLabChartArtworkResolver\.rememberArtwork/);
});

test('Compilation preserves Discogs single cover and original track order while starting first audio early', () => {
  assert.match(comp, /DiscogsClient\.searchCompilations\(/);
  assert.match(comp, /model = summary\.coverUrl \?: summary\.thumbnailUrl/);
  assert.match(comp, /model = detail\.coverUrl/);
  assert.match(comp, /MusicLabFastPath\.find\(/);
  assert.match(comp, /CompilationTrackResolver\.isHardCompatible/);
  assert.match(comp, /CompilationTrackResolver\.resolveTrack\(track, current\.videos\)/);
  // R43 replaces the obsolete bulk-play queue with Hit Parade-style viewport prewarm.
  assert.match(comp, /LaunchedEffect\(detail\.id, index\)/);
  assert.match(comp, /onVisibleTrack\(index\)/);
  assert.match(comp, /visibleWarmGate\.withPermit/);
  assert.match(comp, /MusicLabSectionPlayback\.select\(/);
  assert.doesNotMatch(comp, /Riproduci tutto per brani|resolveAllAndPlay|progressiveAlbumJob/);
  assert.match(policy, /COMPILATION_WARM_TRACKS = 2/);
});

test('Cover architecture and independent original chart loader remain available', () => {
  assert.match(cover, /CoverLabTurboRoundAdmission/);
  assert.match(cover, /session\.preparedVideoSongs/);
  assert.match(hit, /hitparade_\$\{it\.rank\}/);
});
