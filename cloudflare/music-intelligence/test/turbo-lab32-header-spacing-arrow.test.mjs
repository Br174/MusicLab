import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);

test('Turbo LAB32 lowers only the artist circle and artist name in the Cover header', () => {
  assert.match(
    browser,
    /shape = CircleShape,[\s\S]*modifier = Modifier\.padding\(top = 4\.dp\)\.size\(46\.dp\)/,
  );
  assert.match(
    browser,
    /resolvedOriginalArtist\.ifBlank[\s\S]*Modifier\.weight\(1f\)\.padding\(top = 7\.dp\)/,
  );
});

test('Turbo LAB32 slightly enlarges the lateral back arrow without changing its action', () => {
  assert.match(
    browser,
    /Text\(\s*"‹",[\s\S]*navController\.popBackStack\(\)[\s\S]*fontSize = 40\.sp/,
  );
});

test('Turbo LAB32 preserves the approved artist circle size', () => {
  assert.match(browser, /Modifier\.padding\(top = 4\.dp\)\.size\(46\.dp\)/);
});
