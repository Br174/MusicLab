import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);

test('Turbo LAB32 retains the lowered artist identity in approved LAB35 geometry', () => {
  assert.match(
    browser,
    /shape = CircleShape,[\s\S]*modifier = Modifier\.padding\(start = 18\.dp, top = 4\.dp\)\.size\(46\.dp\)/,
  );
  assert.match(
    browser,
    /resolvedOriginalArtist\.ifBlank[\s\S]*Modifier\.weight\(1f\)\.padding\(top = 7\.dp\)/,
  );
});

test('Approved LAB35 enlarges the back arrow without changing its navigation action', () => {
  assert.match(
    browser,
    /Text\(\s*"‹",[\s\S]*navController\.popBackStack\(\)[\s\S]*fontSize = 52\.sp/,
  );
});

test('Approved LAB35 preserves the revised artist circle size', () => {
  assert.match(browser, /Modifier\.padding\(start = 18\.dp, top = 4\.dp\)\.size\(46\.dp\)/);
});
