import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);

test('Turbo LAB32 retains the lowered artist identity in approved LAB34 geometry', () => {
  assert.match(
    browser,
    /shape = CircleShape,[\s\S]*modifier = Modifier\.padding\(start = 10\.dp, top = 4\.dp\)\.size\(48\.dp\)/,
  );
  assert.match(
    browser,
    /resolvedOriginalArtist\.ifBlank[\s\S]*Modifier\.weight\(1f\)\.padding\(top = 7\.dp\)/,
  );
});

test('Approved LAB34 enlarges the back arrow without changing its navigation action', () => {
  assert.match(
    browser,
    /Text\(\s*"‹",[\s\S]*navController\.popBackStack\(\)[\s\S]*fontSize = 46\.sp/,
  );
});

test('Approved LAB34 preserves the revised artist circle size', () => {
  assert.match(browser, /Modifier\.padding\(start = 10\.dp, top = 4\.dp\)\.size\(48\.dp\)/);
});
