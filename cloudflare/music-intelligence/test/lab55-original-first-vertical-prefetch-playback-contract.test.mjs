import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);

test('LAB55 original performer versions are grouped before true covers without forced 1/20 downgrade', () => {
  assert.match(browser, /fun isOriginalPerformerVersion\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /val \(originalVersions, trueCovers\) = displayable\.partition\(::isOriginalPerformerVersion\)/);
  assert.match(browser, /return sortGroup\(originalVersions, sortMode\) \+ sortGroup\(trueCovers, sortMode\)/);
  assert.doesNotMatch(browser, /Stesso interprete dell'originale: mantenuta in fondo a 1\/20/);
  assert.match(browser, /Versioni di \$\{resolvedOriginalArtist\.ifBlank/);
  assert.match(browser, /Cover di altri interpreti/);
});

test('LAB55 restores Score Oldest Newest sorting in Cover', () => {
  assert.match(browser, /DirectSortSelector\(\s*selected = sortMode/);
  assert.match(browser, /DirectVersionSort\.RELEVANCE[\s\S]*compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
  assert.match(browser, /DirectVersionSort\.OLDEST[\s\S]*it\.year \?: Int\.MAX_VALUE/);
  assert.match(browser, /DirectVersionSort\.NEWEST[\s\S]*it\.year \?: Int\.MIN_VALUE/);
});

test('LAB55 appends ten results vertically and preloads exactly one future block', () => {
  assert.match(browser, /visibleMembershipPool[\s\S]*publishPool\.take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
  assert.match(browser, /val target = visibleLimit\.coerceAtLeast\(pageSize\) \+ pageSize/);
  assert.match(browser, /Text\("Carica altri \$pageSize"\)/);
  assert.match(browser, /visibleLimit = target/);
  assert.doesNotMatch(browser, /Text\("‹ Precedenti"\)/);
  assert.doesNotMatch(browser, /Text\("Successivi ›"\)/);
  assert.match(browser, /sourcePrefetchedForVisibleLimit != visibleLimit/);
});

test('LAB59 selected video fast lane reaches native Player without duplicate stream preflight', () => {
  assert.match(browser, /withTimeoutOrNull\(900L\)/);
  assert.match(browser, /connection\.playQueue\(/);
  assert.doesNotMatch(browser, /needsDirectStreamProbe/);
  assert.match(browser, /fun pauseCoverBackgroundForPlayback\(/);
});

test('LAB55 keeps normal playback Cover lane lightweight and one-at-a-time', () => {
  assert.match(browser, /DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1/);
  assert.match(browser, /if \(playbackIsNormallyPlaying\(\)\) \{\s*DIRECT_COVER_PLAYBACK_BATCH_SIZE/);
  assert.match(browser, /fun backgroundWorkBlocked\(\): Boolean = backgroundPausedForPlayback \|\| playbackIsCritical\(\)/);
});
