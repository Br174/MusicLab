import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);
const resolver = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/TitleMeaningResolver.kt',
  'utf8',
);
const sources = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt',
  'utf8',
);
const versionSource = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt',
  'utf8',
);
const ai = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/GeminiCoverVerification.kt',
  'utf8',
);

test('LAB56 makes title identity a pre-ranking Cover gate', () => {
  assert.match(browser, /fun modeAcceptsSeed\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /TitleMeaningResolver\.matchesBaseTitle/);
  assert.match(browser, /return seed\.workRelationConfirmed \|\|\s*independentServices >= 2 \|\|\s*sharedCredits/);
  assert.match(browser, /sharedWorkCreditNames/);
  assert.match(sources, /val identityAccepted =/);
  assert.match(sources, /strictTitleMatch \|\|\s*aiTrusted \|\|\s*explicitRelation \|\|\s*independentSourceConsensus/);
});

test('LAB56 accepts only technical decorations around a same-language base title', () => {
  assert.match(resolver, /val invalidDecoration = segments\.withIndex\(\)\.any/);
  assert.match(resolver, /!allowedDecorationSegment\(segment, aliases\)/);
  assert.match(resolver, /private fun allowedDecorationSegment/);
  assert.match(resolver, /FEATURE_PREFIXES/);
  assert.match(resolver, /"live"/);
  assert.match(resolver, /"remix"/);
  assert.match(resolver, /"remaster"/);
  assert.match(resolver, /"feat"/);
});

test('LAB56 trusts AI Scout for foreign non-literal adaptations while ranking remains separate', () => {
  assert.match(ai, /foreign-language covers and adaptations/);
  assert.match(ai, /COMPLETELY DIFFERENT/);
  assert.match(ai, /not a literal translation/);
  assert.match(sources, /sources = listOf\("AI Scout"\)/);
  assert.match(sources, /workRelationConfirmed = true/);
  assert.match(sources, /evidenceScore = if \(samePerformerAsOriginal\) 2 else 4/);
});

test('LAB56 traditional foreign-title services need second evidence', () => {
  assert.match(sources, /val independentSourceConsensus = candidate\.sources\.distinct\(\)\.size >= 2/);
  assert.match(sources, /candidate\.workRelationConfirmed \|\| candidate\.originalWorkReference/);
  assert.match(sources, /workRelationConfirmed = true,[\s\S]*evidenceScore = 10/);
  assert.match(versionSource, /val workRelationConfirmed: Boolean = false/);
  assert.match(versionSource, /workRelationConfirmed = mergedWorkRelation/);
  assert.match(versionSource, /workRelationConfirmed = true/);
});

test('LAB56 Cover header defaults compact and expands by downward swipe', () => {
  assert.match(browser, /var headerExpanded: Boolean = false/);
  assert.match(browser, /headerSwipeDistance >= 70f -> headerExpanded = true/);
  assert.match(browser, /headerSwipeDistance <= -70f -> headerExpanded = false/);
  assert.match(browser, /if \(movingForward\) \{\s*headerExpanded = false/);
  assert.match(browser, /OutlinedTextField\([\s\S]*label = \{ Text\("Titolo brano"\) \}/);
  assert.match(browser, /Text\(if \(loading\) "…" else "Cerca"\)/);
  assert.match(browser, /DirectSortSelector\([\s\S]*selected = sortMode/);
  assert.match(browser, /if \(headerExpanded\) \{[\s\S]*Text\("Archivio"\)/);
  assert.match(browser, /if \(headerExpanded\) \{[\s\S]*Text\("Fonti"\)/);
});
