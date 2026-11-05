import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const comp = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const search = 'app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const read = path => fs.readFileSync(path, 'utf8');
const week = read(search + 'MusicLabHistoricalHitParade.kt');
const extra = read(search + 'MusicLabHistoricalExtraCharts.kt');
const decoder = read(search + 'MusicLabHistoricHtmlDecoder.kt');
const snapshot = read(search + 'MusicLabEditorialSnapshot.kt');
const titles = read(comp + 'CoverTitleTypography.kt');
const cover = read(comp + 'DiscogsDirectVersionBrowser.kt');
const sources = read(comp + 'CoverPriorityOrder.kt');
const seeds = read(comp + 'DiscogsVersionSource.kt');
const queries = read(comp + 'CoverRoundSearchPlan.kt');

test('HPI weekly/annual source pages decode raw bytes before HTML or musical queries', () => {
  for (const file of [week, extra]) {
    assert.match(file, /MusicLabHistoricHtmlDecoder\.decode\(body\.bytes\(\), response\.header\("Content-Type"\)\)/);
    assert.doesNotMatch(file, /response\.body\?\.string\(\)/);
  }
  assert.match(decoder, /onMalformedInput\(CodingErrorAction\.REPORT\)/);
  assert.match(decoder, /windows-1252/);
  assert.match(decoder, /Charsets\.ISO_8859_1/);
  assert.match(decoder, /Normalizer\.Form\.NFC/);
  assert.match(decoder, /0xEF\.toByte\(\)/);
});

test('Only legacy snapshots with bad Unicode are invalidated, preserving published chart rank and date', () => {
  assert.match(snapshot, /put\("textCodec", 66\)/);
  assert.match(snapshot, /MusicLabHistoricHtmlDecoder\.corrupted\(it\.title\)/);
  assert.match(snapshot, /MusicLabHistoricHtmlDecoder\.corrupted\(it\.artist\)/);
  assert.match(extra, /put\("textCodec", 66\)/);
  assert.match(extra, /o\.optInt\("textCodec", 0\) < 66/);
  assert.match(week, /HistoricalHit\(rank,/);
  assert.match(extra, /HistoricalExtraRow\(rank,/);
});

test('Cover uses NFC typography but keeps original score, sources, video, title admission', () => {
  assert.match(titles, /Normalizer\.Form\.NFC/);
  assert.match(titles, /fun shown\(videoTitle: String, referenceTitle: String\)/);
  assert.match(titles, /if \(folded\(candidatePrefix\) != folded\(original\)\) return video/);
  assert.match(sources, /title = CoverTitleTypography\.prefer\(best\.title, other\.title\)/);
  assert.match(seeds, /trackTitle = CoverTitleTypography\.shown\(candidate\.title, targetTitle\)/);
  assert.match(cover, /referenceTitle = title/);
  assert.match(cover, /CoverTitleTypography\.shown\(seed\.trackTitle, referenceTitle\.orEmpty\(\)\)/);
});

test('Accentless query is a bounded final-round fallback; no new immediate source pass', () => {
  assert.match(queries, /if \(round >= finalRound\)/);
  assert.match(queries, /CoverTitleTypography\.unaccentedQuery\(clean\)/);
  assert.match(queries, /first \+ targeted \+ accentFallback/);
  assert.match(titles, /fun unaccentedQuery\(title: String\): String\?/);
});

test('Real Android JVM tests cover historic mojibake and no foreign-video relabeling', () => {
  const native = read('app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricHtmlDecoderTest.kt');
  const ui = read('app/src/test/kotlin/com/metrolist/music/ui/component/CoverTitleTypographyTest.kt');
  for (const token of ['Miguel Bosé','Loredana Bertè','Chi fermerà la musica','Più su',
    'legacyBytesWithWrongUtf8HttpHeaderNeverLoseAccents','utf8BomIsHonoredEvenWhenHeaderIsMisleading']) {
    assert.ok(native.includes(token), token);
  }
  assert.match(ui, /genuineDifferentWorkAndForeignAdaptationsAreNeverRenamed/);
  assert.match(ui, /firstRoundStaysIdenticalAndLastRoundGetsOnlyOneAccentFallback/);
});
