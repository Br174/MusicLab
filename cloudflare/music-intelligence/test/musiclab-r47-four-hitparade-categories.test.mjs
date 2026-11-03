import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const old = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt','utf8');
const extra = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt','utf8');

test('R47 four options are available from one native dropdown, default remains weekly singles', () => {
  for (const mode of ['WEEKLY_SINGLES','WEEKLY_ALBUMS','ANNUAL_SINGLES','ANNUAL_ALBUMS'])
    assert.match(extra,new RegExp(mode));
  assert.match(extra,/DropdownMenuItem/);
  assert.match(extra,/MusicLabSectionMemory.putText\(context, "hitparade", "chart_mode"/);
  assert.match(extra,/MusicLabHistoricalHitParadeWeeklySinglesScreen/);
  assert.match(old,/MusicLabHistoricalChartModeMenu\(selectedMode, onModeChange\)/);
});
test('R47 does not replace or rewrite original verified weekly single parsing and player', () => {
  assert.match(old,/internal object MusicLabHistoricalHitParade/);
  assert.match(old,/internal fun parseSongs\(html: String\)/);
  assert.match(old,/MusicLabHistoricalHitParade.load\(selected\)/);
  assert.match(old,/MusicLabHitParadeVideoResolver\.find\(/);
  assert.match(old,/MusicLabSectionPlayback.select\(/);
});
test('R47 uses original first-party chart URLs and only published album weeks', () => {
  assert.match(extra,/hp_yends\/hpe\$year.htm/);
  assert.match(extra,/hp_yenda\/lpe\$year.htm/);
  assert.match(extra,/hp_weeka\/\$year\/index.html/);
  assert.match(extra,/publishedAlbumWeeks\(download\(index\), year\)/);
  assert.match(extra,/if \(weeks.isEmpty\(\)\) error/);
  assert.match(extra,/parseAnnualAlbums/);
  assert.match(extra,/parseAnnualSingles/);
  assert.match(extra,/parseWeeklyAlbums/);
});
test('R47 separate persistent category/date/scroll/cache and no autoplay on restore', () => {
  assert.match(extra,/loadSaved\(context, key\)/);
  assert.match(extra,/MusicLabListPositionStore.flush\(positionKey\)/);
  assert.match(extra,/MusicLabSectionWorkCheckpoint.standby/);
  assert.match(extra,/MusicLabSectionWorkCheckpoint.complete/);
  assert.doesNotMatch(extra,/animateScrollToItem/);
});
test('R47 album rows open a search without treating an album as one song', () => {
  assert.match(extra,/if \(mode.isAlbum\) \{/);
  assert.match(extra,/SearchRoutes.resultRoute\(row.title \+ " " \+ displayArtist\)/);
  assert.match(extra,/MusicLabHitParadeDiscCover/);
});
