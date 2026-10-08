import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);
const titleResolver = fs.readFileSync(
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

test('LAB56 same-language work identity accepts only technical decorations', () => {
  assert.match(titleResolver, /val invalidDecoration = segments\.withIndex\(\)\.any/);
  assert.match(titleResolver, /!allowedDecorationSegment\(segment, aliases\)/);
  assert.match(titleResolver, /private fun allowedDecorationSegment/);
  assert.match(titleResolver, /private val FEATURE_PREFIXES/);
  assert.match(titleResolver, /"live"/);
  assert.match(titleResolver, /"remix"/);
  assert.match(titleResolver, /"feat"/);
});

test('LAB56 browser Opera Identity Gate blocks different-title noise unless it has explicit evidence', () => {
  assert.match(browser, /fun modeAcceptsSeed\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /if \(titleOk\) return true/);
  assert.match(browser, /it\.equals\("AI Scout", ignoreCase = true\)/);
  assert.match(browser, /seed\.workRelationConfirmed/);
  assert.match(browser, /independentServices >= 2/);
  assert.match(browser, /DiscogsVersionSource\.sharedWorkCreditNames/);
});

test('LAB56 discovery trusts AI foreign covers but requires extra proof from traditional services', () => {
  assert.match(sources, /val aiTrusted = candidate\.sources\.any \{ it\.equals\("AI Scout", ignoreCase = true\) \}/);
  assert.match(sources, /val independentSourceConsensus = candidate\.sources\.distinct\(\)\.size >= 2/);
  assert.match(sources, /candidate\.workRelationConfirmed \|\| candidate\.originalWorkReference/);
  assert.match(sources, /workRelationConfirmed = true/);
  assert.match(sources, /MusicBrainz/);
  assert.match(sources, /Opera Identity Gate/);
});

test('LAB56 AI Scout explicitly searches non-literal foreign adaptations and has cloud fallback', () => {
  assert.match(ai, /title is COMPLETELY DIFFERENT/);
  assert.match(ai, /not a literal translation/);
  assert.match(sources, /CloudMusicDiscovery\.discoverCover/);
  assert.match(sources, /Trova anche tutte le cover straniere\/adattamenti/);
  assert.match(sources, /AI ammessa per identità opera\/cover straniere/);
});

test('LAB56 preserves same-work relation and shared credits through seed merging', () => {
  assert.match(versionSource, /val workRelationConfirmed: Boolean = false/);
  assert.match(versionSource, /workRelationConfirmed = candidate\.workRelationConfirmed/);
  assert.match(versionSource, /val mergedWorkRelation/);
  assert.match(versionSource, /internal fun sharedWorkCreditNames/);
  assert.match(versionSource, /workRelationConfirmed = true/);
});

test('LAB56 header is compact by default and expands downward without closing screen', () => {
  assert.match(browser, /var headerExpanded: Boolean = false/);
  assert.match(browser, /var headerExpanded by remember\(sessionKey\)/);
  assert.match(browser, /headerSwipeDistance >= 70f -> headerExpanded = true/);
  assert.match(browser, /headerSwipeDistance <= -70f -> headerExpanded = false/);
  assert.doesNotMatch(browser, /closeSwipeDistance >= 110f/);
  assert.match(browser, /if \(headerExpanded\) \{[\s\S]*Cover · MusicLab/);
  assert.match(browser, /OutlinedTextField\([\s\S]*label = \{ Text\("Titolo brano"\) \}/);
  assert.match(browser, /DirectSortSelector\(\s*selected = sortMode/);
  assert.match(browser, /if \(movingForward\) \{\s*headerExpanded = false/);
});
