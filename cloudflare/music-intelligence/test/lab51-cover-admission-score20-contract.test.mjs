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

test('LAB51 broad multi-source discovery remains available before the stricter LAB57 identity gate', () => {
  assert.doesNotMatch(sources, /if \(!sameBaseTitle\(title, candidateTitle\)\) continue/);
  assert.doesNotMatch(sources, /if \(!sameBaseTitle\(title, candidateTitle\)\) return@mapNotNull null/);
  assert.match(sources, /val identityAccepted =/);
  assert.match(sources, /strictTitleMatch \|\|[\s\S]*aiTrusted \|\|[\s\S]*musicBrainzWork \|\|[\s\S]*crossVerifiedRelation \|\|[\s\S]*independentSourceConsensus/);
});

test('LAB51 keeps COVER admission open while semantic weakness is ranked', () => {
  assert.match(sources, /DiscogsDirectMode\.COVER -> true/);
  assert.match(versionSource, /DiscogsDirectMode\.COVER -> true/);
  assert.match(browser, /fun isOriginalPerformerVersion\(seed: DiscogsVersionSeed\)/);
  assert.doesNotMatch(browser, /Stesso interprete dell'originale: mantenuta in fondo a 1\/20/);
  assert.match(
    browser,
    /if \(mode == DiscogsDirectMode\.ORIGINAL && resolvedOriginalArtist\.isBlank\(\)\)/,
  );
  assert.doesNotMatch(
    browser,
    /if \(resolvedOriginalArtist\.isBlank\(\)\) \{\s*error = "Interprete originale di riferimento mancante\."/,
  );
});

test('LAB51 preserves relevance ordering from strongest to weakest', () => {
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
});

test('LAB51 has no residual candidate-level same-performer drop gates', () => {
  assert.doesNotMatch(
    sources,
    /if \(sameArtist\(ref\.artist, originalArtist\)\) return@mapNotNull null/,
  );
  assert.match(
    sources,
    /evidenceScore = if \(samePerformerAsOriginal\) 2 else 4/,
  );
  assert.doesNotMatch(
    versionSource,
    /discover\([\s\S]*?\)\.filter \{ seed ->\s*!sameArtist\(seed\.artist, originalArtist\)/,
  );
  assert.doesNotMatch(
    browser,
    /samePerformerAsOriginal[\s\S]*confidenceScore = 1/,
  );
});


test('LAB57 explicitly separates weak evidence from wrong-work identity', () => {
  assert.match(sources, /LAB57:/);
  assert.match(browser, /LAB57 foreign-service gate/);
  assert.match(browser, /seed\.workRelationConfirmed/);
  assert.match(browser, /independentServices >= 2/);
  assert.match(browser, /sharedWorkCreditNames/);
  assert.match(browser, /LAB59: the year is evidence for ranking/);
});
