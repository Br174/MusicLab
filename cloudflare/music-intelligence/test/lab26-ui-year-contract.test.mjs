import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const dimensions = read('app/src/main/kotlin/com/metrolist/music/constants/Dimensions.kt');
const mainActivity = read('app/src/main/kotlin/com/metrolist/music/MainActivity.kt');
const appearance = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AppearanceSettings.kt');
const settings = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt');
const items = read('app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt');
const home = read('app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt');
const spotifyHome = read('app/src/main/kotlin/com/metrolist/music/ui/component/SpotifyHomeSectionRow.kt');
const homeItalian = read('app/src/main/kotlin/com/metrolist/music/ui/component/ItalianUiText.kt');
const artistAlbums = read('app/src/main/kotlin/com/metrolist/music/ui/screens/artist/ArtistAlbumsScreen.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const yearEnrichment = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverYearEnrichment.kt');
const coverResolver = read('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt');
const geminiCredits = read('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiOriginalVersionCredits.kt');
const titleSearch = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabTitleSearch.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt');
const mini = read('app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt');
const preferenceKeys = read('app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt');

test('LAB26 four-step artwork scale is larger and globally synchronized', () => {
  assert.match(dimensions, /ArtworkSize\.SMALL\s*->\s*124\.dp/);
  assert.match(dimensions, /ArtworkSize\.MEDIUM\s*->\s*152\.dp/);
  assert.match(dimensions, /ArtworkSize\.LARGE\s*->\s*184\.dp/);
  assert.match(dimensions, /ArtworkSize\.VERY_LARGE\s*->\s*224\.dp/);
  assert.match(mainActivity, /rememberEnumPreference\(ArtworkSizeKey, defaultValue = ArtworkSize\.MEDIUM\)/);
  assert.match(mainActivity, /ArtworkSizeRuntime\.current = artworkSize/);
  assert.doesNotMatch(appearance, /GridItemsSizeKey/);
  assert.doesNotMatch(appearance, /showGridSizeDialog/);
  assert.match(settings, /ArtworkSize\.VERY_LARGE -> "Grandissima"/);
});

test('LAB26 protects Home and Album artwork from the global selector', () => {
  assert.match(dimensions, /val HomeGridThumbnailHeight = 128\.dp/);
  assert.match(dimensions, /val HomeListThumbnailSize = 48\.dp/);
  assert.match(dimensions, /val AlbumGridThumbnailHeight = 128\.dp/);
  assert.match(home, /LocalGridThumbnailHeightOverride provides HomeGridThumbnailHeight/);
  assert.match(home, /LocalListThumbnailSizeOverride provides HomeListThumbnailSize/);
  assert.match(home, /val currentGridHeight = HomeGridThumbnailHeight/);
  assert.match(spotifyHome, /HomeGridThumbnailHeight/);
  assert.match(items, /thumbnailHeight = AlbumGridThumbnailHeight/);
  assert.match(items, /thumbnailHeight = if \(item is AlbumItem\) AlbumGridThumbnailHeight else null/);
  assert.match(artistAlbums, /GridCells\.Adaptive\(minSize = AlbumGridThumbnailHeight \+ 24\.dp\)/);
});

test('LAB26 title-tap search switch disables only manual title search', () => {
  assert.match(preferenceKeys, /TitleTapSearchEnabledKey = booleanPreferencesKey\("titleTapSearchEnabled"\)/);
  assert.match(settings, /Ricerca toccando il titolo/);
  assert.match(settings, /rememberPreference\(TitleTapSearchEnabledKey, true\)/);
  assert.match(items, /titleTapSearchEnabled\(\)/);
  assert.match(items, /val titleSearchEnabled = titleTapSearchEnabled\(\)[\s\S]*onTitleClick =\s*if \(titleSearchEnabled\)/);
  assert.match(player, /rememberPreference\(TitleTapSearchEnabledKey, true\)/);
  assert.doesNotMatch(mini, /SearchRoutes\.titleResultRoute/);
  assert.match(titleSearch, /YouTube\.searchSummary\(cleanTitle\)/);
  assert.match(cover, /MusicLabTitleSearch\.fast\(/);
});

test('LAB26 keeps Italian resources complete and duplicate-free', () => {
  const defaults = [
    read('app/src/main/res/values/strings.xml'),
    read('app/src/main/res/values/metrolist_strings.xml'),
  ];
  const italian = [
    read('app/src/main/res/values-it/strings.xml'),
    read('app/src/main/res/values-it/metrolist_strings.xml'),
    read('app/src/main/res/values-it/musiclab_strings.xml'),
    read('app/src/main/res/values-it/musiclab_lab26_complete_it.xml'),
  ];

  const names = (xml) =>
    [...xml.matchAll(/<(?:string|plurals|string-array)\s+name="([^"]+)"/g)].map((match) => match[1]);

  const required = new Set(defaults.flatMap(names));
  const available = new Set();
  const duplicates = [];
  for (const xml of italian) {
    for (const name of names(xml)) {
      if (available.has(name)) duplicates.push(name);
      available.add(name);
    }
  }

  const missing = [...required].filter((name) => !available.has(name));
  assert.deepEqual(missing, [], `missing Italian resources: ${missing.join(', ')}`);
  assert.deepEqual(duplicates, [], `duplicate Italian resources: ${duplicates.join(', ')}`);
  assert.match(mainActivity, /setAppLocale\(this, Locale\.ITALIAN\)/);
});

test('LAB26 normalizes dynamic Home headings without touching media titles', () => {
  assert.match(homeItalian, /"songs of the week" to "Brani della settimana"/);
  assert.match(homeItalian, /"new releases" to "Nuove uscite"/);
  assert.match(homeItalian, /"recommended for you" to "Consigliati per te"/);
  assert.match(home, /italianizeDynamicUiText\(sectionData\.title\)/);
  assert.match(home, /homePage\?\.chips\?\.map \{ it to italianizeDynamicUiText\(it\.title\) \}/);
  assert.match(spotifyHome, /else -> italianizeDynamicUiText\(title\)/);
});

test('LAB26 completes missing Cover years after first paint with provider then AI fallback', () => {
  assert.match(coverResolver, /CoverYearResolver\.resolve\(song\)/);
  assert.match(yearEnrichment, /CoverYearResolver\.resolve\(playable\.song\)/);
  assert.match(yearEnrichment, /CloudMusicDiscovery\.discoverCover\(/);
  assert.match(yearEnrichment, /GeminiOriginalVersionCredits\.enrich\(/);
  assert.match(yearEnrichment, /requireYear = true/);
  assert.match(geminiCredits, /requireYear: Boolean = false/);
  assert.match(geminiCredits, /if \(requireYear\) "year-required" else "credits"/);
  assert.match(geminiCredits, /non lasciare year nullo/);
  assert.match(cover, /CoverYearEnrichment\.enrichMissing\(/);
  assert.match(cover, /yearSource = hit\.source/);
  assert.match(cover, /Anno: \$\{result\.candidate\.year\?\.toString\(\) \?: "ricerca…"\}/);
});
