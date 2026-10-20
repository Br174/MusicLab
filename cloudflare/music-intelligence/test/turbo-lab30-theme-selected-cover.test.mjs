import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const src = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const theme = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/theme/Theme.kt', 'utf8');
const modern = src.split('if (modernCoverCard) {')[1].split('        return\n    }')[0];
const header = src.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];

test('LAB30 selected Cover uses theme accent mixed with theme surface, not a fixed color', () => {
  assert.match(modern, /color = if \(selected\) \{/);
  assert.match(modern, /MaterialTheme\.colorScheme\.primary\.copy\(alpha = 0\.18f\)/);
  assert.match(modern, /\.compositeOver\(MaterialTheme\.colorScheme\.surface\)/);
  assert.match(modern, /else \{\s*MaterialTheme\.colorScheme\.surface/);
  assert.match(src, /import androidx\.compose\.ui\.graphics\.compositeOver/);
});

test('No selected outline, 1px border, or forced glow on a modern Cover card', () => {
  assert.doesNotMatch(modern, /border\s*=/);
  assert.doesNotMatch(modern, /BorderStroke\(/);
  assert.doesNotMatch(modern, /Color\(0x[0-9a-fA-F]{6,8}\)[\s\S]*compositeOver/);
  assert.match(modern, /tonalElevation = 0\.dp/);
});

test('Current app theme controls accent, light/dark and dynamic wallpapers', () => {
  assert.match(theme, /rememberDynamicColorScheme\(/);
  assert.match(theme, /seedColor = themeColor/);
  assert.match(theme, /MaterialTheme\(/);
  assert.match(theme, /colorScheme = colorScheme/);
  assert.match(theme, /dynamicDarkColorScheme\(context\)/);
  assert.match(theme, /dynamicLightColorScheme\(context\)/);
});

test('Header removes redundant reference label without losing original artist', () => {
  assert.doesNotMatch(header, /"Originale di riferimento"/);
  assert.match(header, /resolvedOriginalArtist\.ifBlank/);
  assert.match(header, /modifier = Modifier\.weight\(1f\)\.padding\(top = 3\.dp\)/);
  assert.match(header, /MaterialTheme\.typography\.titleLarge/);
});

test('Back arrow is large and theme-white; portrait and menu remain', () => {
  assert.match(header, /navController\.popBackStack\(\)/);
  assert.match(header, /fontSize = 36\.sp/);
  assert.match(header, /color = MaterialTheme\.colorScheme\.onSurface/);
  assert.match(header, /modifier = Modifier\.size\(46\.dp\)/);
  assert.match(header, /musiclabArtistMenu/);
});

test('Header sits lower than LAB29 to avoid overlaying status icons', () => {
  assert.match(header, /Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 12\.dp, vertical = 9\.dp\)/);
  assert.match(header, /Modifier\.fillMaxWidth\(\)\.height\(58\.dp\)/);
});

test('Approved drag and compact navigation survive', () => {
  assert.match(header, /detectVerticalDragGestures\(/);
  assert.match(header, /coverHeaderDragProgress \+ dragAmount \/ musiclabCoverMotionPx/);
  assert.match(header, /DirectCategorySelector\(/);
  assert.match(header, /musiclabSortMenu/);
  assert.match(header, /musiclabSourcesMenu/);
});

test('Song covers remain clickable and playback bars remain theme colored', () => {
  assert.match(modern, /clickable\(onClick = onPlay\)/);
  assert.match(modern, /MusicLabPlaybackBars\(/);
  assert.match(src, /MaterialTheme\.colorScheme\.primary\.copy\(\s*alpha = if \(animated\)/);
  assert.match(modern, /Color\(0xFF22C871\)/);
  assert.match(modern, /Color\(0xFFFA5757\)/);
});

test('LAB30 only changes visuals; app remains update-in-place signed family', () => {
  const env = fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab30-env.py', 'utf8');
  const wf = fs.readFileSync('.github/workflows/turbo-lab30-theme-selected-cover-ui-apk.yml', 'utf8');
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="1030"/);
  assert.match(env, /MusicLab Turbo LAB 30/);
  assert.match(env, /it\.verlezza\.musiclab\.turbo01/);
  assert.match(wf, /application-label:'MusicLab Turbo LAB 30'/);
  assert.match(wf, /9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49/);
});
