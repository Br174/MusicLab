import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);
const versionSource = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt',
  'utf8',
);
const sources = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt',
  'utf8',
);

test('LAB51 keeps weak/rejected cover candidates and ranks them instead of dropping them', () => {
  assert.doesNotMatch(browser, /filterNot\(::isRejected\)/);
  assert.match(browser, /current\.forEach \{ rawSeed ->/);
  assert.match(browser, /incoming\.forEach \{ rawSeed ->/);
  assert.match(browser, /confidenceScore = 1,/);
  assert.match(browser, /mantenuta in graduatoria a 1\/20/);
  assert.doesNotMatch(browser, /disapprovate escluse/);
});

test('LAB51 uses the complete 1..20 confidence scale end-to-end', () => {
  assert.match(versionSource, /seed\.confidenceScore in 1\.\.20/);
  assert.match(versionSource, /coerceIn\(1, 20\)/);
  assert.match(sources, /coerceIn\(1, 20\)/);
  assert.match(browser, /confidenceScore = 20,/);
  assert.match(browser, /Punteggio 20→1/);
  assert.match(browser, /Affidabilità: \$\{seed\.confidenceScore\}\/20/);
  assert.doesNotMatch(browser, /Affidabilità: \$\{seed\.confidenceScore\}\/10/);
});

test('LAB51 turns title filters into score evidence for broad discovery lanes', () => {
  assert.doesNotMatch(sources, /if \(!sameBaseTitle\(title, candidateTitle\)\) continue/);
  assert.doesNotMatch(sources, /if \(!sameBaseTitle\(title, candidateTitle\)\) return@mapNotNull null/);
  assert.match(sources, /evidenceScore = if \(titleMatches\) 6 else 1/);
});

test('LAB51 keeps COVER admission open while semantic weakness is ranked', () => {
  assert.match(sources, /DiscogsDirectMode\.COVER -> true/);
  assert.match(versionSource, /DiscogsDirectMode\.COVER -> true/);
  assert.match(browser, /samePerformerAsOriginal/);
  assert.match(browser, /Stesso interprete dell'originale: mantenuta in fondo a 1\/20/);
});

test('LAB51 preserves relevance ordering from strongest to weakest', () => {
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
});
