import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const artwork = read('app/src/main/kotlin/com/metrolist/music/constants/ArtworkSize.kt');
const items = read('app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt');
const home = read('app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt');
const artistAlbums = read('app/src/main/kotlin/com/metrolist/music/ui/screens/artist/ArtistAlbumsScreen.kt');
const libraryAlbums = read('app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryAlbumsScreen.kt');
const appearance = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AppearanceSettings.kt');
const settings = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt');
const miniPlayer = read('app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const titleSearch = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabTitleSearch.kt');
const italianUi = read('app/src/main/kotlin/com/metrolist/music/utils/ItalianUiText.kt');
const app = read('app/src/main/kotlin/com/metrolist/music/App.kt');
const activity = read('app/src/main/kotlin/com/metrolist/music/MainActivity.kt');
const contentSettings = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/ContentSettings.kt');

function resourceNames(xml) {
  return new Set(
    [...xml.matchAll(/<(?:string|plurals|string-array)\s+name="([^"]+)"/g)].map((m) => m[1]),
  );
}

test('LAB26 artwork scale is four-level and larger, with protected Home/Album baseline', () => {
  assert.match(artwork, /SMALL\([^)]*124\.dp\)/);
  assert.match(artwork, /MEDIUM\([^)]*152\.dp\)/);
  assert.match(artwork, /LARGE\([^)]*184\.dp\)/);
  assert.match(artwork, /VERY_LARGE\([^)]*224\.dp\)/);
  assert.match(artwork, /ProtectedArtworkHeight:\s*Dp\s*=\s*128\.dp/);
  assert.match(items, /rememberEnumPreference\(ArtworkSizeKey, ArtworkSize\.MEDIUM\)/);
  assert.match(items, /fun currentGridCellMinSize\(\): Dp = currentGridThumbnailHeight\(\) \+ 24\.dp/);
  assert.match(settings, /ArtworkSize\.VERY_LARGE -> "Grandissima"/);
});

test('LAB26 Home and albums are isolated from global artwork sizing', () => {
  assert.match(home, /val currentGridHeight = ProtectedArtworkHeight/);
  assert.doesNotMatch(home, /rememberEnumPreference\(ArtworkSizeKey/);
  assert.match(items, /thumbnailHeightOverride = ProtectedArtworkHeight/);
  assert.match(artistAlbums, /GridThumbnailHeight \+ 24\.dp/);
  assert.match(libraryAlbums, /GridThumbnailHeight \+ 24\.dp/);
  assert.doesNotMatch(appearance, /showGridSizeDialog/);
  assert.doesNotMatch(appearance, /GridItemSize\./);
});

test('LAB26 title-tap search switch gates manual taps but never Cover automatic discovery', () => {
  assert.match(settings, /Ricerca al tocco del titolo/);
  assert.match(settings, /rememberPreference\(TitleTapSearchEnabledKey, true\)/);
  assert.match(items, /TitleTapSearchEnabledKey/);
  assert.match(player, /TitleTapSearchEnabledKey/);
  assert.match(cover, /TitleTapSearchEnabledKey/);
  assert.doesNotMatch(miniPlayer, /titleResultRoute/);
  assert.match(cover, /MusicLabTitleSearch\.fast\(/);
  assert.match(titleSearch, /YouTube\.searchSummary\(cleanTitle\)/);
});

test('LAB26 Cover completes missing years in background using structured then AI fallback', () => {
  assert.match(cover, /CoverYearResolver\.resolve\(playable\.song\)/);
  assert.match(cover, /CloudMusicDiscovery\.resolveYearsBatch\(/);
  assert.match(cover, /GeminiOriginalVersionCredits\.enrich\(/);
  assert.match(cover, /year_youtube_music/);
  assert.match(cover, /year_musiclab_ai/);
  assert.match(cover, /Anno: .*ricerca…/);
  assert.doesNotMatch(cover, /Anno: .*"—"/);
  assert.match(cloud, /suspend fun resolveYearsBatch\(/);
  assert.match(cloud, /Completa ESCLUSIVAMENTE l'anno/);
});

test('LAB26 Italian resource namespace is complete and duplicate-free', () => {
  const basePaths = [
    'app/src/main/res/values/strings.xml',
    'app/src/main/res/values/metrolist_strings.xml',
  ];
  const italianPaths = [
    'app/src/main/res/values-it/strings.xml',
    'app/src/main/res/values-it/metrolist_strings.xml',
  ];

  const base = new Set(basePaths.flatMap((path) => [...resourceNames(read(path))]));
  const italianNames = italianPaths.flatMap((path) => [...resourceNames(read(path))]);
  const italian = new Set(italianNames);
  const missing = [...base].filter((name) => !italian.has(name));
  const duplicates = italianNames.filter((name, index) => italianNames.indexOf(name) !== index);

  assert.deepEqual(missing, [], `Italian resources missing: ${missing.join(', ')}`);
  assert.deepEqual([...new Set(duplicates)], [], `Duplicate Italian resources: ${[...new Set(duplicates)].join(', ')}`);
});

test('LAB26 Italian is default and provider-owned Home headings are normalized', () => {
  assert.match(app, /\?: Locale\.ITALIAN/);
  assert.match(app, /\?: "it"/);
  assert.match(activity, /configuredLanguage[\s\S]*\?: "it"/);
  assert.match(contentSettings, /AppLanguageKey, defaultValue = "it"/);
  assert.match(contentSettings, /ContentLanguageKey, defaultValue = "it"/);
  assert.match(italianUi, /"songs of the week" to "Brani della settimana"/);
  assert.match(italianUi, /"new releases" to "Nuove uscite"/);
  assert.match(home, /italianizeProviderUiText\(sectionData\.title\)/);
});
