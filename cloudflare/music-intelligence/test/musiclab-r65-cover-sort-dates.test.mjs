import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const browser = fs.readFileSync(root + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const sorter = fs.readFileSync(root + 'CoverVisibleSortPolicy.kt', 'utf8');
const years = fs.readFileSync(root + 'CoverVisibleYearResolver.kt', 'utf8');
const display = fs.readFileSync(root + 'CoverCardMetadata.kt', 'utf8');
const native = fs.readFileSync('app/src/test/kotlin/com/metrolist/music/ui/component/CoverVisibleSortPolicyTest.kt', 'utf8');

test('the actual Turbo Punti menu changes a visual sort for original and Cover groups', () => {
  assert.match(browser, /DirectVersionSort\.RELEVANCE to "Punteggio"/);
  assert.match(browser, /DirectVersionSort\.OLDEST to "Più vecchi"/);
  assert.match(browser, /DirectVersionSort\.NEWEST to "Più nuovi"/);
  assert.match(browser, /CoverVisibleSortPolicy\.sort\(/);
  assert.match(browser, /publishedCoverSnapshots/);
  assert.match(browser, /category, sortMode,/);
  assert.match(browser, /sortMode\.name/);
  assert.match(sorter, /"OLDEST"/);
  assert.match(sorter, /"NEWEST"/);
  assert.match(sorter, /musiclabScore100/);
  assert.match(sorter, /dateKey\(it\) == null/);
  assert.match(native, /oldestUsesRealReleaseDatesAndKeepsUnknownAtEnd/);
  assert.match(native, /newestUsesPublicationDateButUnknownStillGoesLast/);
});

test('late validated metadata actually reaches existing published immutable Cover snapshots', () => {
  assert.match(browser, /refreshed\.year else poster\.year \?: refreshed\.year/);
  assert.match(browser, /sourceNames = \(poster\.sourceNames \+ refreshed\.sourceNames\)\.distinct\(\)/);
  assert.match(browser, /refreshed\.releaseDate else poster\.releaseDate \?: refreshed\.releaseDate/);
  assert.match(browser, /poster\.year == null && refreshed\.year != null/);
  assert.match(browser, /session\.publishedCoverSnapshots = publishedCoverSnapshots/);
  assert.match(browser, /session\.publishedOriginalSnapshots = publishedOriginalSnapshots/);
});

test('chronological enrichment is lazy, bounded and uses verified musical year, not upload timestamp', () => {
  assert.match(browser, /if \(mode != DiscogsDirectMode\.COVER \|\| !isTurbo \|\|/);
  assert.match(browser, /sortMode == DirectVersionSort\.RELEVANCE\) return@LaunchedEffect/);
  assert.match(browser, /CoverVisibleYearResolver\.discover\(/);
  assert.match(browser, /coverYearAttempted/);
  assert.match(browser, /delay\(120L\)/);
  assert.match(years, /CoverYearResolver\.resolve\(song\)/);
  assert.match(years, /song\?\.album\?\.id/);
  assert.match(years, /withTimeoutOrNull\(2_400L\)/);
  assert.match(years, /DiscogsClient\.getRelease/);
  assert.match(years, /getSharedPreferences/);
  assert.doesNotMatch(years, /publishedAt|uploadDate|videoUpload/);
  assert.doesNotMatch(years, /Gemini|estimate|AI|playNow\(|seekTo\(/);
});

test('unverifiable amateur video keeps explicit unknown date, never fake time or forced admission', () => {
  assert.match(browser, /"Data non disponibile"/);
  assert.match(browser, /publicationYear\?\.let \{ "Anno: \$it" \}/);
  assert.match(display, /"youtube music"/);
  assert.match(years, /CoverCardMetadata\.publicationYear\(row\) != null/);
  assert.match(native, /unverifiedAmateurKeepsUnknownYearInsteadOfInventingUploadYear/);
});
