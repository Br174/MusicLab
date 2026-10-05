import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const title = read('app/src/main/kotlin/com/metrolist/music/ui/component/TitleMeaningResolver.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('LAB44 reserves top confidence for identified original recording', () => {
  assert.match(source, /originalArtistMatch[\s\S]*TitleMeaningMatch\.EXACT[\s\S]*DiscogsVersionKind\.STUDIO -> 10/);
  assert.match(source, /artistIsOriginal && titleMatch == TitleMeaningMatch\.EXACT && kind == DiscogsVersionKind\.STUDIO -> 10/);
  assert.match(source, /coerceAtMost\(if \(artistIsOriginal\) 10 else 9\)/);
  assert.match(source, /coerceAtMost\(if \(originalArtistMatch\) 10 else 9\)/);
  assert.doesNotMatch(source, /Interprete diverso dall'originale"[\s\S]{0,100}score \+= 3/);
});

test('LAB44 relevance sort is score/evidence driven, not age driven', () => {
  const start = browser.indexOf('DirectVersionSort.RELEVANCE ->');
  const end = browser.indexOf('DirectVersionSort.OLDEST ->', start);
  const block = browser.slice(start, end);
  assert.match(block, /confidenceScore/);
  assert.match(block, /originalWorkReference/);
  assert.match(block, /sourceNames\.distinct\(\)\.size/);
  assert.doesNotMatch(block, /\.year/);
  assert.match(browser, /rebuildStableOrder\(\)[\s\S]*relevanceChanged/);
});

test('LAB44 COVER.INFO parser ignores counters and follows relation target performer', () => {
  assert.match(cover, /\.field-title a\[href\^=\/en\/song\/\]/);
  assert.match(cover, /COVER_INFO_RELATION_TARGET/);
  assert.match(cover, /initial-details/);
  assert.match(cover, /follow-up-details/);
  assert.match(cover, /row\?\.selectFirst\("\.field-artists a\[href\*=\'\/artist\/\'\]"\)/);
  assert.match(cover, /candidateTitle\.matches\(Regex\("""\\d\+"""\)\)/);
  assert.match(cover, /originalWorkReference =[\s\S]*CoverInfoRelationRole\.INITIAL/);
});

test('LAB44 can recover original reference even when search starts from a cover', () => {
  assert.match(title, /fun trailingArtistHint/);
  assert.match(title, /fun stripTrailingArtistHint/);
  assert.match(browser, /explicitArtistHint = TitleMeaningResolver\.trailingArtistHint\(title\)/);
  assert.match(browser, /firstOrNull \{ it\.originalWorkReference && it\.artist\.isNotBlank\(\) \}/);
  assert.match(cover, /DiscogsDirectMode\.COVER -> true/);
  assert.match(source, /DiscogsDirectMode\.COVER -> true/);
});

test('LAB44 makes video-ready state visible immediately and green', () => {
  assert.match(browser, /val jobs =[\s\S]*chunk\.map \{ seed ->[\s\S]*launch \{/);
  assert.match(browser, /Update this card immediately/);
  assert.match(browser, /jobs\.forEach \{ it\.join\(\) \}/);
  assert.match(browser, /videoReady -> Color\(0xFF2E7D32\)/);
  assert.match(browser, /videoReady -> "Tocca per riprodurre"/);
});

test('LAB44 preserves LAB43B and Player buono', () => {
  assert.match(cover, /addQueryParameter\("per-page-songs", "200"\)/);
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 24/);
  assert.match(browser, /DIRECT_VIDEO_PARALLELISM = 6/);
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  assert.match(player, /extractionBundle\.fastExtractor\.extract/);
  assert.match(player, /PoTokenProviderKind\.WEB_BOTGUARD/);
});

test('LAB44 APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB44"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB44"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab44"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB44"/);
});
