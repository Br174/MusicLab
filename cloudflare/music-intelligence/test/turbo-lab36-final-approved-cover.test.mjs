import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const code = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const header = code.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
const category = code.split('@Composable\nprivate fun DirectCategorySelector(')[1].split('@Composable\nprivate fun DirectSortSelector(')[0];
const chip = code.split('@Composable\nprivate fun DirectChip(')[1].split('@Composable\nprivate fun DirectFilterField(')[0];
const cover = category.split('if (twoLine) {')[1].split('    } else {')[0];

test('final Cover category row consists of exactly five equal width 56dp high chips', () => {
  assert.match(cover, /categories\.forEach \{ \(category, label, count\) ->/);
  assert.match(cover, /Modifier\.weight\(1f\)\.height\(56\.dp\)/);
  assert.doesNotMatch(cover, /relativeWidths|forEachIndexed|horizontalScroll/);
  const categories = category.match(/Triple\(DirectVersionCategory\./g) || [];
  assert.equal(categories.length, 5);
});

test('final category labels include Remix only in Turbo Cover', () => {
  assert.match(category, /DirectVersionCategory\.REMIX, if \(twoLine\) "Remix" else "Mix\/Remix"/);
  assert.match(header, /twoLine = true/);
  assert.match(code, /DirectVersionCategory\.REMIX -> "Remix"/);
});

test('Punti and Fonti have matching width, height and corner shape, with distinct menus', () => {
  const controls = header.split('Box(\n                        modifier = Modifier')[1];
  assert.match(header, /Box\(modifier = Modifier\.weight\(1f\)\.height\(54\.dp\)\) \{[\s\S]*?"Punti ▾"/);
  assert.match(header, /Box\(modifier = Modifier\.weight\(1f\)\.height\(54\.dp\)\) \{[\s\S]*?"Fonti ▾"/);
  assert.match(header, /shape = RoundedCornerShape\(14\.dp\)/);
  assert.match(header, /expanded = musiclabSortMenu/);
  assert.match(header, /expanded = musiclabSourcesMenu/);
});

test('original sort and source actions remain available', () => {
  for (const label of ['Punteggio', 'Più vecchi', 'Più nuovi', 'Tutte le fonti', 'Dettagli delle fonti']) {
    assert.ok(header.includes('"' + label + '"'), label);
  }
  assert.match(header, /sortMode = value/);
  assert.match(header, /showSourceDiagnostics = true/);
});

test('search retains typing and submit while losing its outlined border', () => {
  assert.match(code, /import androidx\.compose\.foundation\.text\.BasicTextField/);
  assert.match(header, /Surface\([\s\S]*?Modifier\.weight\(3f\)\.height\(54\.dp\)/);
  assert.match(header, /BasicTextField\([\s\S]*?onValueChange = \{ title = it \}/);
  assert.match(header, /if \(!loading\) runSearch\(forceRefresh = true\)/);
  assert.doesNotMatch(header.split('// LAB36: matching borderless upper controls')[1].split('// LAB34: a compact')[0], /OutlinedTextField|BorderStroke|border\(/);
});

test('matching borderless matte controls retain theme colors and compact shape', () => {
  const top = header.split('// LAB36: matching borderless upper controls')[1].split('// LAB34: a compact')[0];
  assert.ok((top.match(/color = MaterialTheme\.colorScheme\.surfaceVariant/g)||[]).length >= 3);
  assert.ok((top.match(/RoundedCornerShape\(14\.dp\)/g)||[]).length >= 3);
  assert.match(chip, /MaterialTheme\.colorScheme\.surfaceVariant/);
  assert.match(chip, /RoundedCornerShape\(if \(count != null\) 14\.dp else 10\.dp\)/);
});

test('compact and expanded states share unchanged full-size filter layout', () => {
  assert.match(header, /height\(\(62f \* musiclabCoverExpandedProgress\)\.dp\)/);
  assert.match(header, /clipToBounds\(\)/);
  assert.match(header, /requiredHeight\(62\.dp\)/);
  assert.match(header, /Spacer\(Modifier\.height\(10\.dp\)\)[\s\S]*?DirectCategorySelector\(/);
  assert.match(header, /detectVerticalDragGestures\(/);
  assert.match(header, /session\.headerExpanded = headerExpanded/);
});

test('approved header keeps safe area arrow title position and smaller artist portrait', () => {
  assert.match(code, /Modifier\.statusBarsPadding\(\)/);
  assert.match(header, /navController\.popBackStack\(\)/);
  assert.match(header, /fontSize = 52\.sp/);
  assert.match(header, /Modifier\.padding\(start = 18\.dp, top = 4\.dp\)\.size\(46\.dp\)/);
  assert.match(header, /fontSize = 19\.sp/);
  assert.match(header, /musiclabArtistMenu/);
});

test('visual-only update does not remove diagnostic or Cover category selection', () => {
  assert.match(header, /lab31AudioDiagnosticReport\(\)/);
  assert.match(header, /category = selected/);
  assert.match(header, /session\.category = selected/);
  assert.match(header, /scope\.launch \{ listState\.scrollToItem\(0\) \}/);
  assert.match(cover, /onClick = \{ onSelected\(category\) \}/);
});
