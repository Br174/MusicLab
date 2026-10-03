import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const screens = read('app/src/main/kotlin/com/metrolist/music/ui/screens/Screens.kt');
const navUi = read('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt');
const navBuilder = read('app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt');
const intelligence = read('app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const compilation = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const playerMenu = read('app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt');
const songMenu = read('app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt');
const youtubeSongMenu = read('app/src/main/kotlin/com/metrolist/music/ui/menu/YouTubeSongMenu.kt');
const account = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt');
const musicService = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const mic = read('app/src/main/res/drawable/cover_microphone.xml');
const discSearch = read('app/src/main/res/drawable/compilation_disc_search.xml');
const uab = read('uab-project.env');

test('LAB30 puts icon-only Cover and Compilation around Search in main navigation', () => {
  assert.match(screens, /object Cover : Screens/);
  assert.match(screens, /iconIdInactive = R\.drawable\.cover_microphone/);
  assert.match(screens, /object Compilation : Screens/);
  assert.match(screens, /iconIdInactive = R\.drawable\.compilation_disc_search/);
  assert.match(screens, /MainScreens = listOf\(Home, Cover, Search, Compilation, ListenTogether, Library\)/);
  assert.match(navUi, /screen != Screens\.Cover/);
  assert.match(navUi, /screen != Screens\.Compilation/);
  assert.match(mic, /strokeLineCap="round"/);
  assert.match(discSearch, /strokeLineCap="round"/);
});

test('LAB30 keeps one shared Cover engine with current-song and manual entry', () => {
  assert.match(intelligence, /text = "Cover"/);
  assert.match(intelligence, /R\.drawable\.cover_microphone/);
  assert.match(intelligence, /CoverNavigationBridge\.open/);
  assert.doesNotMatch(intelligence, /text = "Compilation"/);
  assert.match(navBuilder, /composable\(CoverNavigationBridge\.ROUTE\)/);
  assert.match(navBuilder, /CoverSearchScreen\(/);
  assert.match(navBuilder, /title = ""/);
  assert.match(cover, /var effectiveRequest by remember/);
  assert.match(cover, /Cerca cover per titolo/);
  assert.match(cover, /manualCoverQuery/);
  assert.match(cover, /effectiveRequest = CoverSearchRequest/);
});

test('LAB30 persists Compilation results pagination filters detail and exact scroll offset', () => {
  assert.match(compilation, /object CompilationSessionStore/);
  assert.match(compilation, /var results: List<DiscogsCompilationSummary>/);
  assert.match(compilation, /var currentPage: Int/);
  assert.match(compilation, /var totalDiscogsResults: Int/);
  assert.match(compilation, /var activeSearch: CompilationSearchCriteria\?/);
  assert.match(compilation, /var detail: DiscogsCompilationDetail\?/);
  assert.match(compilation, /var listIndex: Int/);
  assert.match(compilation, /var listOffset: Int/);
  assert.match(compilation, /rememberLazyListState/);
  assert.match(compilation, /firstVisibleItemIndex/);
  assert.match(compilation, /firstVisibleItemScrollOffset/);
  assert.match(compilation, /snapshotFlow/);
  assert.match(compilation, /CompilationSessionStore\.results = results/);
  assert.match(compilation, /CompilationSessionStore\.activeSearch = criteria/);
  assert.match(compilation, /state = resultListState/);
  assert.match(compilation, /page = currentPage \+ 1/);
  assert.match(compilation, /Totale Discogs/);
});

for (const [name, menu] of [
  ['PlayerMenu', playerMenu],
  ['SongMenu', songMenu],
  ['YouTubeSongMenu', youtubeSongMenu],
]) {
  test('LAB30 keeps Spotify action position stable in ' + name, () => {
    assert.doesNotMatch(menu, /\+ if \(com\.metrolist\.spotify\.Spotify\.isAuthenticated\(\)\)/);
    assert.match(menu, /text = stringResource\(R\.string\.spotify_add_to_playlist\)/);
    assert.match(menu, /enabled = com\.metrolist\.spotify\.Spotify\.isAuthenticated\(\)/);
  });
}

test('LAB30 keeps YouTube Music settings action visible while disconnected', () => {
  assert.match(account, /title = \{ Text\(stringResource\(R\.string\.switch_youtube_channel\)\) \}/);
  assert.match(account, /enabled = isLoggedIn/);
  assert.doesNotMatch(account, /if \(isLoggedIn\) \{\s*Material3SettingsItem\(\s*title = \{ Text\(stringResource\(R\.string\.switch_youtube_channel\)\)/);
});

test('LAB30 raises current-song buffer reserve and makes next-track prewarm subordinate', () => {
  assert.match(musicService, /PLAYBACK_START_BUFFER_MS = 3_000/);
  assert.match(musicService, /PLAYBACK_REBUFFER_MS = 8_000/);
  assert.match(musicService, /SMART_PRELOAD_PREFIX_BYTES = 256L \* 1024L/);
  assert.match(musicService, /SMART_PRELOAD_STABLE_BUFFER_MS = 25_000L/);
  assert.match(musicService, /SMART_PRELOAD_RESUME_BUFFER_MS = 20_000L/);
  assert.match(musicService, /setBufferDurationsMs\(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS\)/);
  assert.match(musicService, /player\.totalBufferedDuration < SMART_PRELOAD_RESUME_BUFFER_MS/);
});

test('LAB30 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB30"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB30"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab30"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB30"/);
});
