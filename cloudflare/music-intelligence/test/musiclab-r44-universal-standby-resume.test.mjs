import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const checkpoint = read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionWorkCheckpoint.kt');
const policy = read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionWorkPolicy.kt');
const compilation = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const ccache = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationResolvedCache.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const round = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt');
const hit = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt');
const chart = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt');
const online = read('app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt');
const search = read('app/src/main/kotlin/com/metrolist/music/viewmodels/MusicLabOnlineSearchSnapshot.kt');
const screen = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt');

test('R44 durable checkpoints have explicit running, standby and complete boundaries', () => {
  assert.match(checkpoint, /enum class Phase \{ RUNNING, STANDBY, COMPLETE \}/);
  assert.match(checkpoint, /getSharedPreferences\(PREFS, Context\.MODE_PRIVATE\)/);
  assert.match(checkpoint, /\.commit\(\)/);
  assert.match(checkpoint, /fun begin\(/);
  assert.match(checkpoint, /fun advance\(/);
  assert.match(checkpoint, /fun standby\(/);
  assert.match(checkpoint, /fun complete\(/);
  assert.match(policy, /fun shouldResumeInterrupted\(/);
  assert.doesNotMatch(checkpoint, /playNow|playQueue|MusicService|SpotifyAccessToken/);
});

test('R44 Hit Parade and Charts reuse completed local results, pause failed/cancelled requests', () => {
  assert.match(hit, /MusicLabEditorialSnapshot\.loadHistorical\(context, dateKey\)/);
  assert.match(hit, /MusicLabSectionWorkPolicy\.shouldLoad/);
  assert.match(hit, /MusicLabSectionWorkCheckpoint\.complete/);
  assert.match(hit, /catch \(cancel: CancellationException\)/);
  assert.match(chart, /MusicLabEditorialSnapshot\.loadChart\(context, title\)/);
  assert.match(chart, /MusicLabSectionWorkPolicy\.shouldLoad/);
  assert.match(chart, /MusicLabSectionWorkCheckpoint\.standby/);
});

test('R44 Compilation commits verified songs, release fingerprint and interrupted paging', () => {
  assert.match(compilation, /MusicLabCompilationResolvedCache\.save\(context, current, resolvedTracks\)/);
  assert.match(compilation, /MusicLabCompilationResolvedCache\.load\(context, current\)/);
  assert.match(compilation, /MusicLabSectionWorkCheckpoint\.advance\(context, "compilation:tracks"/);
  assert.match(compilation, /MusicLabSectionWorkPolicy\.shouldResumeInterrupted/);
  assert.match(compilation, /resumeInterrupted = true/);
  assert.match(compilation, /MusicLabCompilationSnapshot\.save\(context, MusicLabCompilationSnapshot\.Saved\(/);
  assert.match(ccache, /fingerprint\(track\)/);
  assert.match(ccache, /current\.tracks\.getOrNull\(position\)/);
  assert.match(ccache, /row\.optString\("fingerprint"\)/);
  assert.doesNotMatch(ccache, /playerConnection|\.play\(|password|TokenKey/);
});

test('R44 full search section suspends network and restores completed Spotify/YTM metadata', () => {
  assert.match(screen, /setSectionActive\(false\)/);
  assert.match(online, /combine\(filter, sectionActive\)/);
  assert.match(online, /sectionActive\.collectLatest/);
  assert.match(online, /pagingJob\?\.cancel\(\)/);
  assert.match(online, /MusicLabOnlineSearchSnapshot\.loadSummary/);
  assert.match(online, /MusicLabOnlineSearchSnapshot\.saveSummary/);
  assert.match(online, /MusicLabOnlineSearchSnapshot\.loadPage/);
  assert.match(online, /MusicLabOnlineSearchSnapshot\.savePage/);
  assert.match(search, /is SongItem/);
  assert.match(search, /is AlbumItem/);
  assert.match(search, /is ArtistItem/);
  assert.match(search, /is PlaylistItem/);
  assert.doesNotMatch(search, /SpotifyAccessToken|LastFMSecret|\.playNow\(/);
});

test('R44 Spotify Library stops all ViewModel loads on leave and restores persisted metadata', () => {
  const spotify = read('app/src/main/kotlin/com/metrolist/music/viewmodels/SpotifyViewModel.kt');
  const mixes = read('app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryMixScreen.kt');
  const playlists = read('app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryPlaylistsScreen.kt');
  assert.match(spotify, /fun setLibraryVisible\(visible: Boolean\)/);
  assert.match(spotify, /libraryLoadJob\?\.cancel\(\)/);
  assert.match(spotify, /restoreLibraryCache\(\)/);
  assert.match(spotify, /saveLibraryTracks\(CACHE_KEY_LIKED/);
  assert.match(spotify, /saveLibraryTracks\(CACHE_KEY_TOP/);
  assert.match(spotify, /savePlaylistsToCache\(collected\)/);
  assert.match(mixes, /setLibraryVisible\(false\)/);
  assert.match(playlists, /setLibraryVisible\(false\)/);
  assert.doesNotMatch(spotify, /playNow\(|playQueue\(/);
});

test('R44 reuses native Cover pause and saved round recovery without modifying the Cover engine', () => {
  assert.match(cover, /LAB57 hard Cover lifecycle barrier/);
  assert.match(cover, /searchJob\?\.cancel\(\)/);
  assert.match(cover, /videoPreloadJob\?\.cancel\(\)/);
  assert.match(cover, /CoverRoundStorage\.load\(context, sessionKey\)/);
  assert.match(cover, /runSearch\(appendRound = true, resumePreparation = true\)/);
  assert.match(round, /AtomicFile\(/);
  assert.match(round, /deferredRows/);
});
