import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const original = read('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt');
const uab = read('uab-project.env');

test('LAB35 still routes Cover and Originali to the same direct Discogs browser', () => {
  assert.match(cover, /DiscogsDirectVersionBrowser\([\s\S]*DiscogsDirectMode\.COVER/);
  assert.match(original, /DiscogsDirectVersionBrowser\([\s\S]*DiscogsDirectMode\.ORIGINAL/);
  assert.doesNotMatch(browser, /Gemini|AiCoverFlowResolver|MusicBrainz|Conferma|Rifiuta|Verifica meglio/);
});

test('LAB35 restores semantic version filters instead of generic release-form fields', () => {
  for (const label of ['Tutto', 'Studio', 'Live', 'Mix']) {
    assert.ok(browser.includes('DirectChip("' + label), 'missing semantic chip ' + label);
  }
  for (const obsolete of ['Artista cover (opzionale)', 'Pubblicazione / album', 'Numero di catalogo', 'Etichetta', 'Genere', 'Stile']) {
    assert.ok(!browser.includes('label = "' + obsolete + '"'), 'obsolete drawer field remains: ' + obsolete);
  }
});

test('LAB35 has compact relevance oldest newest sorting with server-wide order', () => {
  assert.match(browser, /DirectVersionSort\.RELEVANCE -> null to null/);
  assert.match(browser, /DirectVersionSort\.OLDEST -> "year" to "asc"/);
  assert.match(browser, /DirectVersionSort\.NEWEST -> "year" to "desc"/);
  assert.match(browser, /DirectChip\("Rilevanti"/);
  assert.match(browser, /DirectChip\("Più vecchi"/);
  assert.match(browser, /DirectChip\("Più nuovi"/);
  assert.match(client, /sort: String\? = "year"/);
  assert.match(client, /sort\?\.takeIf/);
});

test('LAB35 lists Discogs search releases immediately instead of opening every release before first paint', () => {
  assert.match(source, /seedFromSearchSummary/);
  assert.match(source, /releasePage\.items\.mapNotNull/);
  const loadStart = source.indexOf('suspend fun loadVersionPage');
  const loadEnd = source.indexOf('suspend fun enrichSeedMetadata', loadStart);
  const directLoad = source.slice(loadStart, loadEnd);
  assert.doesNotMatch(directLoad, /getRelease\(/);
  assert.match(browser, /resolveSeedForPlayback/);
});

test('LAB35 protects Discogs HTTP 429 with bounded backoff and Retry-After', () => {
  assert.match(client, /response\.code == 429/);
  assert.match(client, /Retry-After/);
  assert.match(client, /DISCOGS_MAX_RETRIES = 3/);
  assert.match(client, /DISCOGS_BACKOFF_BASE_MS/);
});

test('LAB35 progressively enriches visible cards without blocking pagination', () => {
  assert.match(browser, /enrichSeedMetadata/);
  assert.match(browser, /delay\(1_100\)/);
  assert.match(browser, /enrichedReleaseIds/);
});

test('LAB35 keeps automatic page continuation and exposes all Discogs pages', () => {
  assert.match(browser, /DIRECT_VERSION_PAGE_SIZE = 20/);
  assert.match(browser, /DIRECT_VERSION_PREFETCH_DISTANCE = 8/);
  assert.match(browser, /visibleResults\.size - lastVisible <= DIRECT_VERSION_PREFETCH_DISTANCE/);
  assert.match(browser, /currentPage < totalPages/);
  assert.match(browser, /loadNextPage\(\)/);
  assert.doesNotMatch(browser, /results\.size < 5[\s\S]*attempts < 4/);
  assert.match(browser, /Release Discogs:/);
});

test('LAB35 increases mini-player clearance for both shared pages', () => {
  assert.match(browser, /DIRECT_VERSION_BOTTOM_SAFE_DP = 260/);
  assert.match(browser, /bottom = DIRECT_VERSION_BOTTOM_SAFE_DP\.dp/);
});

test('LAB35 Originali is still locked to one singer and no longer depends on per-track artist details to list results', () => {
  assert.match(source, /DiscogsDirectMode\.ORIGINAL -> originalArtist\.trim/);
  assert.match(source, /DiscogsDirectMode\.ORIGINAL -> originalArtist\.trim\(\)\.ifBlank \{ parsedArtist \}/);
  const loadStart = source.indexOf('suspend fun loadVersionPage');
  const loadEnd = source.indexOf('suspend fun enrichSeedMetadata', loadStart);
  assert.doesNotMatch(source.slice(loadStart, loadEnd), /seedsFromRelease/);
});

test('LAB35 duplicate-safe list keys and semantic fingerprints remain active', () => {
  assert.match(browser, /discogs_direct_\$\{mode\.name\}_\$\{index\}_\$\{seed\.releaseId\}/);
  assert.match(source, /summaryVersionFingerprint/);
  assert.match(source, /masterId\?\.let \{ "m\$it" \}/);
  assert.match(source, /groupBy \{ it\.fingerprint \}/);
});

test('LAB35 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB35"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB35"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab35"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB35"/);
});
