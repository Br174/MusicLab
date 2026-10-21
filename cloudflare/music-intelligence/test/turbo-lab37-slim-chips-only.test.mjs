import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const selector = source.split('@Composable\nprivate fun DirectCategorySelector(')[1].split('@Composable\nprivate fun DirectSortSelector(')[0];
const upper = source.split('// LAB36: matching borderless upper controls')[1].split('// LAB34: a compact')[0];
const categories = selector.split('if (twoLine) {')[1].split('    } else {')[0];

test('LAB37: all five lower category controls are uniformly 56dp high', () => {
  assert.match(categories, /categories\.forEach \{ \(category, label, count\) ->/);
  assert.match(categories, /modifier = Modifier\.weight\(1f\)\.height\(56\.dp\)/);
  assert.doesNotMatch(categories, /height\(72\.dp\)|relativeWidths|horizontalScroll/);
});

test('LAB37: same width, rounding, colors and two-line labels as approved LAB36', () => {
  assert.match(categories, /Modifier\.weight\(1f\)/);
  assert.match(selector, /if \(twoLine\) "Remix" else "Mix\/Remix"/);
  for (const label of ['Tutto','Studio','Live','Stranieri']) {
    assert.ok(selector.includes('"'+label+'"'));
  }
  assert.match(source, /shape = RoundedCornerShape\(if \(count != null\) 14\.dp else 10\.dp\)/);
  assert.match(source, /color = if \(selected\) MaterialTheme\.colorScheme\.primaryContainer else MaterialTheme\.colorScheme\.surfaceVariant/);
});

test('LAB37: upper buttons and search keep original 54dp size and menu behaviors', () => {
  assert.match(upper, /"Punti ▾"/);
  assert.match(upper, /"Fonti ▾"/);
  assert.match(upper, /Modifier\.weight\(1f\)\.height\(54\.dp\)/);
  assert.match(upper, /Modifier\.weight\(3f\)\.height\(54\.dp\)/);
  assert.match(upper, /musiclabSortMenu/);
  assert.match(upper, /musiclabSourcesMenu/);
  assert.match(upper, /BasicTextField/);
});

test('LAB37: compact and expanded share exactly the same lower category row', () => {
  const header=source.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
  assert.match(header, /height\(\(62f \* musiclabCoverExpandedProgress\)\.dp\)/);
  assert.match(header, /DirectCategorySelector\([\s\S]*twoLine = true/);
  assert.match(source, /Modifier\.statusBarsPadding\(\)/);
});
