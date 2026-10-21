import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const src = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const turbo = src.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
const category = src.split('@Composable\nprivate fun DirectCategorySelector(')[1].split('@Composable\nprivate fun DirectSortSelector(')[0];
const chip = src.split('@Composable\nprivate fun DirectChip(')[1].split('@Composable\nprivate fun DirectFilterField(')[0];

test('LAB34 respects real status bar insets for Turbo Cover only', () => {
  assert.match(src, /import androidx\.compose\.foundation\.layout\.statusBarsPadding/);
  assert.match(turbo, /if \(isTurbo && mode == DiscogsDirectMode\.COVER\) Modifier\.statusBarsPadding\(\) else Modifier/);
  assert.doesNotMatch(turbo, /padding\(top = (?:24|32|40)\.dp\)/);
});

test('LAB34 approved larger back arrow preserves navigation and theme colors', () => {
  assert.match(turbo, /Text\(\s*"‹",[\s\S]*?navController\.popBackStack\(\)[\s\S]*?fontSize = 52\.sp/);
  assert.match(turbo, /fontSize = 52\.sp,\s*color = MaterialTheme\.colorScheme\.onSurface/);
});

test('LAB34 artist circle is subtly enlarged and shifted right', () => {
  assert.match(turbo, /shape = CircleShape,[\s\S]*?Modifier\.padding\(start = 18\.dp, top = 4\.dp\)\.size\(52\.dp\)/);
  assert.match(turbo, /musiclabArtistPoster/);
  assert.match(turbo, /contentScale = ContentScale\.Crop/);
});

test('LAB34 smaller title retains center baseline, boldness and truncation', () => {
  assert.match(turbo, /resolvedOriginalArtist\.ifBlank[\s\S]*?Modifier\.weight\(1f\)\.padding\(top = 7\.dp\)[\s\S]*?fontSize = 19\.sp,[\s\S]*?fontWeight = FontWeight\.Bold/);
  assert.match(turbo, /overflow = TextOverflow\.Ellipsis/);
});

test('LAB34 positions taller category chips below artist without blank void', () => {
  assert.match(turbo, /Spacer\(Modifier\.height\(10\.dp\)\)[\s\S]*?DirectCategorySelector\([\s\S]*?twoLine = true/);
  assert.match(turbo, /Modifier\.fillMaxWidth\(\)\.height\(58\.dp\)/);
});

test('LAB34 stacks live category counts centered beneath labels, keeping existing filter logic', () => {
  assert.match(category, /twoLine: Boolean = false/);
  assert.match(category, /results\.count \{ directDisplayCategory\(it\) == DirectVersionCategory\.STUDIO \}/);
  assert.match(category, /count = count/);
  assert.match(category, /onClick = \{ onSelected\(category\) \}/);
  assert.match(chip, /horizontalAlignment = Alignment\.CenterHorizontally/);
  assert.match(chip, /text = count\.toString\(\)/);
});

test('LAB35 confines horizontal scrolling to legacy non-Turbo category controls', () => {
  assert.match(category, /else \{[\s\S]*horizontalScroll\(rememberScrollState\(\)\)/);
  for (const label of ['Tutto', 'Studio', 'Live', 'Mix/Remix', 'Stranieri']) {
    assert.ok(category.includes('"' + label + '"'), 'Missing label: ' + label);
  }
  assert.match(category, /categories\.forEach/);
});

test('LAB34 preserves old chip appearance outside Turbo Cover', () => {
  assert.match(chip, /if \(count == null\) \{/);
  assert.match(chip, /MaterialTheme\.typography\.labelSmall/);
  assert.match(chip, /Modifier\.padding\(horizontal = 9\.dp, vertical = 5\.dp\)/);
  assert.match(chip, /MaterialTheme\.colorScheme\.primaryContainer/);
  assert.doesNotMatch(chip, /Color\(0x[0-9a-fA-F]+\)/);
});

test('LAB34 keeps original swipe gestures and audio diagnostics', () => {
  assert.match(turbo, /detectVerticalDragGestures\(/);
  assert.match(turbo, /coverHeaderDragProgress \+ dragAmount \/ musiclabCoverMotionPx/);
  assert.match(turbo, /lab31AudioDiagnosticReport\(\)/);
});
