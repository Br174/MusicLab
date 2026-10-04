import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const models = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt');
const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const gemini = read('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiCoverVerification.kt');
const uab = read('uab-project.env');

test('LAB36 keeps original Discogs artwork and adds details without YouTube thumbnails', () => {
  assert.match(browser, /model = seed\.coverUrl/);
  assert.match(browser, /Text\("Dettagli"\)/);
  assert.match(browser, /DiscogsVersionDetailsDialog/);
  assert.match(browser, /Crediti: non disponibili/);
});

test('LAB36 ranks every retained candidate on a 1-10 confidence scale', () => {
  assert.match(source, /val confidenceScore: Int = 1/);
  assert.match(source, /confidenceScore = score\.coerceIn\(1, 9\)/);
  assert.match(source, /confidenceScore = \(seed\.confidenceScore \+ 1\)\.coerceAtMost\(10\)/);
  assert.match(browser, /Punteggio 10→1/);
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
});

test('LAB36 does not hard-reject original singer or Various summaries before track verification', () => {
  const seedStart = source.indexOf('private fun seedFromSearchSummary');
  const seedEnd = source.indexOf('private fun splitSearchTitle', seedStart);
  const seedBlock = source.slice(seedStart, seedEnd);
  assert.doesNotMatch(seedBlock, /return null[\s\S]*sameArtist\(artist, originalArtist\)/);
  assert.match(source, /Interprete generico\/Various: da verificare nella tracklist/);
});

test('LAB36 collapses identical studio recordings independently of release/master packaging', () => {
  assert.match(source, /if \(kind == DiscogsVersionKind\.STUDIO\) \{[\s\S]*""[\s\S]*\} else \{/);
  assert.match(source, /groupBy \{ it\.fingerprint \}/);
});

test('LAB36 parses Discogs credits and compares them with original-work credits', () => {
  assert.match(models, /data class DiscogsCredit/);
  assert.match(client, /optJSONArray\("extraartists"\)/);
  assert.match(source, /loadOriginalWorkCredits/);
  assert.match(source, /applySharedWorkCreditEvidence/);
  assert.match(source, /Crediti dell'opera coincidenti/);
});

test('LAB36 uses grounded AI as a broad foreign-version scout and then verifies on Discogs', () => {
  assert.match(browser, /GeminiCoverVerification\.discover/);
  assert.match(source, /loadReferenceSeeds/);
  assert.match(gemini, /MAX_DISCOVERY_RESULTS = 30/);
  assert.match(gemini, /weak-but-real leads/);
  assert.match(browser, /Cerco anche adattamenti e versioni in altre lingue/);
});

test('LAB36 pre-resolves videos and stamps each YouTube id as single-use', () => {
  assert.match(resolver, /excludedVideoIds: Set<String> = emptySet\(\)/);
  assert.match(resolver, /filterNot \{ it\.id in excludedVideoIds \}/);
  assert.match(browser, /session\.usedVideoIds\.add\(resolved\.song\.id\)/);
  assert.match(browser, /resolvedVideoId/);
  assert.match(browser, /Video pronto:/);
});

test('LAB36 retains LAB35 paging, filters and persistent session state', () => {
  assert.match(browser, /DIRECT_VERSION_PAGE_SIZE = 20/);
  assert.match(browser, /DIRECT_VERSION_PREFETCH_DISTANCE = 8/);
  for (const label of ['Tutto', 'Studio', 'Live', 'Mix']) {
    assert.ok(browser.includes('DirectChip("' + label));
  }
  assert.match(browser, /session\.listIndex/);
  assert.match(browser, /session\.listOffset/);
  assert.match(browser, /session\.results = results/);
});

test('LAB36 APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB36"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB36"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab36"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB36"/);
});
