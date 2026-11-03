import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const base = 'app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const title = fs.readFileSync(base + 'MusicLabHistoricalHeaderTitle.kt','utf8');
const weekly = fs.readFileSync(base + 'MusicLabHistoricalHitParade.kt','utf8');
const extra = fs.readFileSync(base + 'MusicLabHistoricalExtraCharts.kt','utf8');

test('R49 uses separated two-line header with a smaller secondary date', () => {
  assert.match(title, /Column\(verticalArrangement = Arrangement\.spacedBy\(3\.dp\)\)/);
  assert.match(title, /fontSize = 17\.sp/);
  assert.match(title, /fontSize = 13\.sp/);
  assert.match(title, /MaterialTheme\.colorScheme\.onSurfaceVariant/);
  assert.match(title, /TextOverflow\.Ellipsis/);
  assert.doesNotMatch(title, /Text\("Hit Parade.*\+.*date/);
});

test('R49 weekly displays loaded published date; annual displays only year', () => {
  assert.match(title, /if \(mode\.annual\)/);
  assert.match(title, /SimpleDateFormat\("d MMMM yyyy", Locale\.ITALIAN\)/);
  assert.match(title, /Calendar\.YEAR/);
  assert.match(weekly, /chart\?\.week\?\.millis \?: selected\.timeInMillis/);
  assert.match(extra, /chart\?\.actualDate \?: selectedMillis/);
});

test('R49 preserves sticky TopAppBar and four chart categories', () => {
  assert.match(weekly, /TopAppBar\(title = \{[\s\S]*?MusicLabHistoricalHeaderTitle\(selectedMode,/);
  assert.match(extra, /TopAppBar\(title = \{[\s\S]*?MusicLabHistoricalHeaderTitle\(mode,/);
  assert.match(title, /if \(mode\.isAlbum\) "Hit Parade album" else "Hit Parade singoli"/);
  assert.match(extra, /MusicLabHistoricalChartModeMenu\(mode, onModeChange\)/);
  assert.match(weekly, /MusicLabHitParadeVideoMenu/);
  assert.match(extra, /MusicLabHitParadeVideoMenu/);
  assert.doesNotMatch(weekly + extra, /TopAppBar\(title = \{ Text\("Hit Parade Italiana"\)/);
});
