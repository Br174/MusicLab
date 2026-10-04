import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const original = read('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const uab = read('uab-project.env');

test('LAB34 routes Cover and Originali only through the shared direct Discogs browser', () => {
  assert.match(cover, /internal fun CoverSearchScreen[\s\S]*DiscogsDirectVersionBrowser\([\s\S]*DiscogsDirectMode\.COVER/);
  assert.match(original, /internal fun OriginalVersionScreen[\s\S]*DiscogsDirectVersionBrowser\([\s\S]*DiscogsDirectMode\.ORIGINAL/);
  assert.match(cover, /private fun LegacyAiCoverSearchScreenUnused/);
  assert.match(original, /private fun LegacyAiOriginalVersionScreenUnused/);
});

test('LAB34 direct browser has no AI confirmation/rejection workflow', () => {
  assert.doesNotMatch(browser, /Conferma|Rifiuta|Verifica meglio|Gemini|AiCoverFlowResolver|MusicBrainz/);
  assert.match(browser, /Cerca direttamente nel database Discogs/);
});

test('LAB34 Cover and Originali share the same sticky filters pagination and mini-player safe area', () => {
  assert.match(browser, /DIRECT_VERSION_PAGE_SIZE = 20/);
  assert.match(browser, /DIRECT_VERSION_PREFETCH_DISTANCE = 8/);
  assert.match(browser, /DIRECT_VERSION_BOTTOM_SAFE_DP = 176/);
  assert.match(browser, /contentPadding = PaddingValues\([\s\S]*bottom = DIRECT_VERSION_BOTTOM_SAFE_DP\.dp/);
  assert.match(browser, /layoutInfo\.visibleItemsInfo\.lastOrNull\(\)\?\.index/);
  assert.match(browser, /results\.size - lastVisible <= DIRECT_VERSION_PREFETCH_DISTANCE/);
  assert.match(browser, /loadNextPage\(\)/);
  assert.match(browser, /results\.isEmpty\(\)[\s\S]*currentPage < totalPages[\s\S]*loadNextPage\(\)/);
});

test('LAB34 prevents ALL-style duplicate lazy keys even if duplicate data reaches UI', () => {
  assert.match(browser, /items\(\s*count = results\.size/);
  assert.match(browser, /key = \{ index ->/);
  assert.match(browser, /discogs_direct_\$\{mode\.name\}_\$\{index\}_\$\{seed\.releaseId\}/);
  assert.doesNotMatch(browser, /selectedTab\.name/);
});

test('LAB34 uses only native Discogs filters for direct version search', () => {
  for (const field of ['track', 'artist', 'release_title', 'year', 'format', 'country', 'label', 'genre', 'style', 'catno']) {
    assert.ok(client.includes('addQueryParameter("' + field + '"'), 'missing native Discogs field ' + field);
  }
  assert.match(browser, /Pubblicazione \/ album/);
  assert.match(browser, /Numero di catalogo/);
  assert.match(browser, /Genere/);
  assert.match(browser, /Stile/);
});

test('LAB34 Originali locks one singer while Cover excludes the original singer', () => {
  assert.match(source, /DiscogsDirectMode\.ORIGINAL -> originalArtist\.trim/);
  assert.match(source, /DiscogsDirectMode\.ORIGINAL ->\s*originalArtist\.isNotBlank\(\) && sameArtist\(seed\.artist, originalArtist\)/);
  assert.match(source, /DiscogsDirectMode\.COVER ->\s*originalArtist\.isBlank\(\) \|\| !sameArtist\(seed\.artist, originalArtist\)/);
  assert.match(browser, /Artista fisso:/);
});

test('LAB34 deduplicates identical studio recordings but preserves version context for live remix acoustic', () => {
  assert.match(source, /DiscogsVersionKind\.STUDIO -> ""/);
  assert.match(source, /DiscogsVersionKind\.LIVE,[\s\S]*DiscogsVersionKind\.REMIX,[\s\S]*DiscogsVersionKind\.ACOUSTIC,[\s\S]*canonicalReleaseContext\(releaseTitle\)/);
  assert.match(source, /groupBy \{ it\.fingerprint \}/);
});

test('LAB34 keeps listing lightweight and uses Compilation resolver only when user plays', () => {
  assert.match(source, /getRelease\(token, summary\.id, includeMasterVideos = false\)/);
  assert.match(client, /includeMasterVideos: Boolean = true/);
  assert.match(browser, /CompilationTrackResolver\.resolveTrack/);
  assert.match(browser, /beginPlaybackPriorityBurst\("discogs-direct-version"\)/);
});

test('LAB34 preserves Player Veloce 3 unchanged', () => {
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 900/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 4_000/);
});

test('LAB34 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB34"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB34"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab34"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB34"/);
});
