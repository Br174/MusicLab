import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const connection = read('app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt');
const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const screen = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const uab = read('uab-project.env');

test('LAB31 restores the device-approved LAB25 fast-start buffer profile', () => {
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 1_250/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 4_000/);
  assert.match(service, /SMART_PRELOAD_TRACKS = 1/);
  assert.match(service, /SMART_PRELOAD_PREFIX_BYTES = 384L \* 1024L/);
  assert.match(service, /SMART_PRELOAD_STABLE_BUFFER_MS = 12_000L/);
  assert.match(service, /SMART_PRELOAD_RESUME_BUFFER_MS = 8_000L/);
});

test('LAB31 Player Veloce 1 gives explicit playback a bounded priority burst', () => {
  assert.match(service, /fun beginPlaybackPriorityBurst/);
  assert.match(service, /PLAYBACK_PRIORITY_BURST_MIN_MS = 2_000L/);
  assert.match(service, /PLAYBACK_PRIORITY_BURST_MAX_MS = 8_000L/);
  assert.match(service, /smartPreloadJob\?\.cancel\(\)/);
  assert.match(service, /preCacheJob\?\.cancel\(\)/);
  assert.match(service, /sponsorBlockJob\?\.cancel\(\)/);
  assert.match(service, /crossfadeMessage\?\.cancel\(\)/);
  assert.match(service, /scheduleSmartPlaybackPreload\(\)/);
  assert.match(service, /startSponsorBlockForCurrentTrack\(\)/);
});

test('LAB31 applies priority to play now play next and previous navigation', () => {
  assert.match(service, /beginPlaybackPriorityBurst\("play-now"\)/);
  assert.match(connection, /beginPlaybackPriorityBurst\("play"\)/);
  assert.match(connection, /beginPlaybackPriorityBurst\("skip-next"\)/);
  assert.match(connection, /beginPlaybackPriorityBurst\("skip-previous"\)/);
  assert.match(connection, /beginPlaybackPriorityBurst\("restart"\)/);
});

test('LAB31 Compilation resolver has a bounded fast-first race before deep fallback', () => {
  assert.match(resolver, /fastMusicLabRace/);
  assert.match(resolver, /CompletableDeferred/);
  assert.match(resolver, /withTimeoutOrNull\(3_050L\)/);
  assert.match(resolver, /YouTube\.searchSummary\(query\)/);
  assert.match(resolver, /YouTube\.search\(query, YouTube\.SearchFilter\.FILTER_SONG\)/);
  assert.match(resolver, /jobs\.forEach \{ it\.cancel\(\) \}/);
});

test('LAB31 keeps Compilation search visible and filters collapsible', () => {
  assert.match(screen, /var filtersExpanded/);
  assert.match(screen, /label = \{ Text\("Cerca compilation"\) \}/);
  assert.match(screen, /Text\(if \(filtersExpanded\) "⌃" else "⌄"\)/);
  assert.match(screen, /if \(filtersExpanded\)/);
  assert.match(screen, /CompilationFilterDropdown\(\s*label = "Anno"/);
});

test('LAB31 prefetches more Discogs pages before the user reaches the end', () => {
  assert.match(screen, /layoutInfo\.visibleItemsInfo\.lastOrNull\(\)\?\.index/);
  assert.match(screen, /results\.size - lastVisible <= 12/);
  assert.match(screen, /loadNextPage\(\)/);
});

test('LAB31 keeps list content clear of mini-player and bottom navigation', () => {
  const safePaddingMatches = screen.match(/PaddingValues\([^)]*bottom = 176\.dp[^)]*\)/g) ?? [];
  assert.ok(safePaddingMatches.length >= 2, 'result and detail lists must both reserve bottom safe space');
});

test('LAB31 highlights the tapped Compilation track immediately', () => {
  assert.match(screen, /selectedTrackIndex = index/);
  assert.match(screen, /val selected = index == selectedTrackIndex/);
  assert.match(screen, /primary\.copy\(alpha = 0\.11f\)/);
});

test('LAB31 stops Compilation-side background work before track playback and warms one next track later', () => {
  assert.match(screen, /paginationJob\?\.cancel\(\)/);
  assert.match(screen, /fullAudioLookupJob\?\.cancel\(\)/);
  assert.match(screen, /nextTrackWarmJob\?\.cancel\(\)/);
  assert.match(screen, /beginPlaybackPriorityBurst\("compilation-track-tap"\)/);
  assert.match(screen, /while \(playerConnection\?\.isPlaybackPriorityBurstActive\(\) == true\)/);
  assert.match(screen, /playerConnection\?\.playNext\(next\.song\.toMediaItem\(\)\)/);
});

test('LAB31 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB31"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB31"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab31"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB31"/);
});
