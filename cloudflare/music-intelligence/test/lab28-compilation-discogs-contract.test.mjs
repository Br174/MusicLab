import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const keys = read('app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const models = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt');
const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const language = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationLanguageHeuristics.kt');
const screen = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const bridge = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt');
const nav = read('app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt');
const menu = read('app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt');
const uab = read('uab-project.env');

test('LAB28 keeps Discogs credentials local and uses authenticated official API calls', () => {
  assert.match(keys, /DiscogsTokenKey = stringPreferencesKey\("discogsToken"\)/);
  assert.match(client, /https:\/\/api\.discogs\.com/);
  assert.match(client, /Authorization", "Discogs token=\$token"/);
  assert.match(client, /User-Agent", USER_AGENT/);
});

test('LAB28 searches only compilations and exposes native Discogs filters', () => {
  assert.match(client, /addQueryParameter\("type", "release"\)/);
  assert.match(client, /addQueryParameter\("format", "Compilation"\)/);
  assert.match(client, /addQueryParameter\("year"/);
  assert.match(client, /addQueryParameter\("genre"/);
  assert.match(client, /addQueryParameter\("style"/);
  assert.match(client, /addQueryParameter\("country"/);
  assert.doesNotMatch(screen, /Album\s*\/\s*Singolo\s*\/\s*EP/);
});

test('LAB28 transports Discogs artwork tracklist and videos into MusicLab', () => {
  assert.match(models, /data class DiscogsCompilationDetail/);
  assert.match(models, /val tracks: List<DiscogsTrack>/);
  assert.match(models, /val videos: List<DiscogsVideo>/);
  assert.match(client, /optJSONArray\("tracklist"\)/);
  assert.match(client, /videoList\(\)/);
  assert.match(client, /\/masters\/\$masterId/);
  assert.match(client, /optJSONArray\("images"\)/);
});

test('LAB28 prefers Discogs YouTube links then falls back to MusicLab providers', () => {
  assert.match(resolver, /bestDiscogsTrackVideo/);
  assert.match(resolver, /YouTube\.queue\(videoIds = listOf\(id\)\)/);
  assert.match(resolver, /source = "Discogs → YouTube"/);
  assert.match(resolver, /YouTube\.searchSummary\(query\)/);
  assert.match(resolver, /FILTER_SONG/);
  assert.match(resolver, /YouTubeWebSearch\.search\(query\)/);
  assert.match(resolver, /FILTER_VIDEO/);
});

test('LAB28 recognizes a possible one-file full compilation audio', () => {
  assert.match(resolver, /findFullAudio/);
  assert.match(resolver, /FULL_AUDIO_MARKERS/);
  assert.match(resolver, /full album\|full compilation\|complete album/);
  assert.match(screen, /Riproduci compilation completa/);
});

test('LAB28 screen provides requested filters and progressive playable tracklist', () => {
  assert.match(screen, /label = "Anno"/);
  assert.match(screen, /label = "Genere"/);
  assert.match(screen, /label = "Stile"/);
  assert.match(screen, /label = "Nazione"/);
  assert.match(screen, /label = "Lingua"/);
  assert.match(screen, /Year\.now\(\)\.value downTo 1900/);
  assert.match(language, /matchesOrUnknown/);
  assert.match(screen, /itemsIndexed\([\s\S]*detail\.tracks/);
  assert.match(screen, /Tocca per trovare e riprodurre/);
  assert.match(screen, /resolvedTracks\.size/);
});

test('LAB28 uses MusicLab player and can save a compilation as a local playlist', () => {
  assert.match(screen, /playerConnection\?\.playNow/);
  assert.match(screen, /playerConnection\?\.playQueue/);
  assert.match(screen, /ListQueue/);
  assert.match(screen, /PlaylistEntity/);
  assert.match(screen, /playlistId = "discogs_" \+ current\.id/);
  assert.match(screen, /addSongsToPlaylist/);
  assert.match(screen, /Salva compilation/);
});

test('LAB28 has a full-screen navigation action beside MusicLab intelligence actions', () => {
  assert.match(bridge, /ROUTE = "compilation_hub"/);
  assert.match(nav, /CompilationNavigationBridge\.bind\(navController\)/);
  assert.match(nav, /composable\(CompilationNavigationBridge\.ROUTE\)/);
  assert.match(menu, /text = "Compilation"/);
  assert.match(menu, /CompilationNavigationBridge\.open\(\)/);
});

test('LAB28 APK identity stays isolated from LAB27 Candidate and Mother', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB28"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB28"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab28"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB28"/);
});
