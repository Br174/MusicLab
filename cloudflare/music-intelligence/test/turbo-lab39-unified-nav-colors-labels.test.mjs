import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const nav = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt', 'utf8');
const screens = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/Screens.kt', 'utf8');
const cover = fs.readFileSync('app/src/main/res/drawable/cover_microphone.xml', 'utf8');
const compilation = fs.readFileSync('app/src/main/res/drawable/compilation_disc_search.xml', 'utf8');

test('LAB39 Material3 NavigationBarItem tint applies equally to all five icons', () => {
  const rails = nav.match(/icon = \{\s*Icon\([\s\S]*?\)\s*\}/g) ?? [];
  assert.equal(rails.length, 2, 'rail and bottom bar use shared Icon behavior');
  for (const icon of rails) {
    assert.match(icon, /painter = painterResource\(id = iconRes\)/);
    assert.doesNotMatch(icon, /tint =|Color.White|Screens.Cover|Screens.Compilation/);
  }
  assert.doesNotMatch(nav, /background\.luminance\(\)|screen == Screens\.Cover \|\| screen == Screens\.Compilation/);
});

test('LAB39 all five bottom labels use one compact uniform size', () => {
  const bottom = nav.split('fun AppNavigationBar(')[1];
  assert.match(bottom, /label = if \(!slimNav\)/);
  assert.match(bottom, /fontSize = 10\.sp/);
  assert.match(bottom, /letterSpacing = 0\.sp/);
  assert.match(bottom, /maxLines = 1/);
  assert.match(bottom, /overflow = TextOverflow\.Ellipsis/);
  assert.doesNotMatch(bottom, /screen != Screens\.Cover|screen != Screens\.Compilation/);
});

test('LAB39 all five tabs retain existing routing, icon geometry and selected state', () => {
  for (const name of ['Home','Cover','Search','Compilation','Library']) {
    assert.ok(screens.includes('object ' + name + ' : Screens('), name);
  }
  assert.match(nav, /NavigationBarItem\(\s*selected = isSelected/);
  assert.match(nav, /onItemClick\(screen, currentIsSelected\)/);
  for (const xml of [cover, compilation]) {
    assert.match(xml, /android:width="24dp"/);
    assert.match(xml, /android:height="24dp"/);
    assert.match(xml, /android:strokeColor="@android:color\/white"/);
  }
  assert.match(compilation, /M9\.4,8\.1L16,12L9\.4,15\.9Z/);
  assert.match(cover, /M12,3\.2C9\.9,3\.2/);
});

test('LAB39 bottom nav still follows theme background and selected styles', () => {
  assert.match(nav, /MaterialTheme\.colorScheme\.surfaceContainer/);
  assert.match(nav, /MaterialTheme\.colorScheme\.onSurfaceVariant/);
  assert.match(nav, /contentColor = contentColor/);
  assert.match(nav, /selected = isSelected/);
});
