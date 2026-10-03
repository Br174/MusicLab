import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const dimensions = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/constants/Dimensions.kt',
  'utf8',
);
const app = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/App.kt',
  'utf8',
);
const mainActivity = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/MainActivity.kt',
  'utf8',
);
const settings = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt',
  'utf8',
);
const keys = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/constants/MusicIntelligenceKeys.kt',
  'utf8',
);
const items = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/Items.kt',
  'utf8',
);
const home = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt',
  'utf8',
);
const cover = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
  'utf8',
);
const yearBackfill = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverYearBackfill.kt',
  'utf8',
);
const italianUi = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/ItalianUiText.kt',
  'utf8',
);
const mini = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt',
  'utf8',
);

function resourceNames(xml) {
  return new Set(
    [...xml.matchAll(/<(?:string|plurals|string-array)\s+name="([^"]+)"/g)].map((m) => m[1]),
  );
}

test('LAB26 artwork scale is larger and Home plus Album are insulated', () => {
  assert.match(dimensions, /ArtworkSize\.SMALL -> 124\.dp/);
  assert.match(dimensions, /ArtworkSize\.MEDIUM -> 152\.dp/);
  assert.match(dimensions, /ArtworkSize\.LARGE -> 184\.dp/);
  assert.match(dimensions, /ArtworkSize\.VERY_LARGE -> 224\.dp/);
  assert.match(dimensions, /val AlbumGridThumbnailHeight: Dp = 128\.dp/);
  assert.match(dimensions, /val HomeGridThumbnailHeight: Dp = 128\.dp/);
  assert.match(dimensions, /val HomeSmallGridThumbnailHeight: Dp = 104\.dp/);
  assert.match(home, /HomeGridThumbnailHeight else HomeSmallGridThumbnailHeight/);
  assert.match(items, /gridHeightOverride = AlbumGridThumbnailHeight/);
});

test('LAB26 global artwork runtime mirrors the persisted preference', () => {
  assert.match(app, /prefs\[ArtworkSizeKey\]/);
  assert.match(app, /ArtworkSize\.valueOf\(value\)/);
  assert.match(app, /ArtworkSizeRuntime\.current = it/);
});

test('LAB26 title-tap search has a default-on master preference for title taps only', () => {
  assert.match(keys, /TitleTapSearchEnabledKey = booleanPreferencesKey\("titleTapSearchEnabled"\)/);
  assert.match(settings, /rememberPreference\(TitleTapSearchEnabledKey, true\)/);
  assert.match(settings, /Ricerca al tocco del titolo/);
  assert.match(items, /titleTapSearchEnabled/);
  assert.doesNotMatch(mini, /SearchRoutes\.titleResultRoute/);
});

test('LAB26 Cover years are completed in background streaming-first then AI', () => {
  assert.match(cover, /CoverYearBackfill\.resolve\(/);
  assert.match(yearBackfill, /CoverYearResolver\.resolve\(playable\.song\)/);
  assert.match(yearBackfill, /CloudMusicDiscovery\.discoverCover\(/);
  assert.match(yearBackfill, /CloudMusicDiscovery\.verifyCandidate\(/);
  assert.match(yearBackfill, /GeminiOriginalVersionCredits\.enrich\(/);
  assert.match(yearBackfill, /NON lasciare year nullo/);
  assert.match(cover, /text = "Anno: /);
});

test('LAB26 Italian resources fully cover the merged Android resource set with no duplicates', () => {
  const primaryDefault = resourceNames(fs.readFileSync('app/src/main/res/values/strings.xml', 'utf8'));
  const primaryIt = resourceNames(fs.readFileSync('app/src/main/res/values-it/strings.xml', 'utf8'));
  const meldDefault = resourceNames(fs.readFileSync('app/src/main/res/values/metrolist_strings.xml', 'utf8'));
  const meldIt = resourceNames(fs.readFileSync('app/src/main/res/values-it/metrolist_strings.xml', 'utf8'));

  const defaultMerged = new Set([...primaryDefault, ...meldDefault]);
  const italianMerged = new Set([...primaryIt, ...meldIt]);

  assert.deepEqual([...defaultMerged].filter((name) => !italianMerged.has(name)), []);

  const crossDuplicates = [...primaryIt].filter((name) => meldIt.has(name));
  assert.deepEqual(crossDuplicates, []);
});

test('LAB26 forces Italian UI/provider locale and normalizes provider-controlled Home labels', () => {
  assert.match(mainActivity, /setAppLocale\(this, Locale\.ITALIAN\)/);
  assert.match(app, /hl = "it"/);
  assert.match(home, /italianizeUiLabel\(sectionData\.title\)/);
  assert.match(italianUi, /"songs of the week" to "Brani della settimana"/);
  assert.match(italianUi, /"new releases" to "Nuove uscite"/);
});

test('LAB26 very-large selector is explicitly Grandissima', () => {
  assert.match(settings, /ArtworkSize\.VERY_LARGE -> "Grandissima"/);
});
