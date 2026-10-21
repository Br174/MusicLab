import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const src = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const header = src.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
const selector = src.split('@Composable\nprivate fun DirectCategorySelector(')[1].split('@Composable\nprivate fun DirectSortSelector(')[0];
const chip = src.split('@Composable\nprivate fun DirectChip(')[1].split('@Composable\nprivate fun DirectFilterField(')[0];

test('LAB35 five Cover filters have an explicitly width-bounded non-scroll row', () => {
  const turbo = selector.split('if (twoLine) {')[1].split('    } else {')[0];
  assert.match(turbo, /BoxWithConstraints\(modifier = Modifier\.fillMaxWidth\(\)\)/);
  assert.match(turbo, /Row\([\s\S]*?Modifier\.fillMaxWidth\(\)/);
  assert.doesNotMatch(turbo, /horizontalScroll|rememberScrollState/);
});

test('LAB36 all five filters get exactly equal width and same height', () => {
  const turbo = selector.split('if (twoLine) {')[1].split('    } else {')[0];
  assert.doesNotMatch(turbo, /relativeWidths|forEachIndexed|horizontalScroll/);
  assert.match(turbo, /categories\.forEach \{ \(category, label, count\) ->/);
  assert.match(turbo, /Modifier\.weight\(1f\)\.height\(72\.dp\)/);
});

test('LAB35 adaptive narrow-screen font keeps all categories visible', () => {
  assert.match(selector, /val smallScreen = maxWidth < 360\.dp/);
  assert.match(selector, /compact = smallScreen/);
  assert.match(chip, /fontSize = if \(compact\) 10\.sp else 12\.sp/);
  assert.match(chip, /maxLines = 1/);
});

test('LAB35 filters preserve counts centered underneath labels', () => {
  assert.match(chip, /horizontalAlignment = Alignment\.CenterHorizontally/);
  assert.match(chip, /text = label,[\s\S]*?text = count\.toString\(\)/);
  assert.match(chip, /verticalArrangement = Arrangement\.Center/);
});

test('LAB35 all 5 real category modes remain selectable', () => {
  for (const mode of ['ALL','STUDIO','LIVE','REMIX','FOREIGN']) {
    assert.match(selector, new RegExp('DirectVersionCategory\\.' + mode));
  }
  for (const label of ['Tutto','Studio','Live','Remix','Stranieri']) {
    assert.ok(selector.includes('"' + label + '"'));
  }
  assert.match(selector, /onClick = \{ onSelected\(category\) \}/);
});

test('LAB35 regular non-Cover chip layout remains the original scroll row', () => {
  const legacy = selector.split('    } else {')[1];
  assert.match(legacy, /horizontalScroll\(rememberScrollState\(\)\)/);
  assert.match(chip, /if \(count == null\)/);
});

test('LAB35 back arrow larger, with original navigation action', () => {
  assert.match(header, /"‹",[\s\S]*navController\.popBackStack\(\)[\s\S]*fontSize = 52\.sp/);
  assert.match(header, /color = MaterialTheme\.colorScheme\.onSurface/);
});

test('LAB35 portrait larger and shifted right together with artist name', () => {
  assert.match(header, /shape = CircleShape,[\s\S]*?Modifier\.padding\(start = 18\.dp, top = 4\.dp\)\.size\(46\.dp\)/);
  assert.match(header, /resolvedOriginalArtist\.ifBlank[\s\S]*?fontSize = 19\.sp/);
  assert.match(header, /overflow = TextOverflow\.Ellipsis/);
});

test('LAB35 status bar and header category spacing remain approved', () => {
  assert.match(src, /Modifier\.statusBarsPadding\(\)/);
  assert.match(header, /Spacer\(Modifier\.height\(10\.dp\)\)[\s\S]*DirectCategorySelector\(/);
});

test('LAB35 audio search player and previous source ordering remain untouched', () => {
  assert.match(header, /lab31AudioDiagnosticReport\(\)/);
  assert.match(header, /coverHeaderDragProgress \+ dragAmount \/ musiclabCoverMotionPx/);
});
