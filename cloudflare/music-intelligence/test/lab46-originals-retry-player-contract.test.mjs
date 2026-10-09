import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('failed red video rows are partitioned to the bottom', () => {
  assert.match(browser, /categoryFiltered\.partition \{ seed ->/);
  assert.match(browser, /seed\.videoResolutionChecked && seed\.resolvedVideoId\.isNullOrBlank\(\)/);
  assert.match(browser, /return usableOrPending \+ failedVideo/);
});

test('red Video non trovato text starts a manual deep retry', () => {
  assert.match(browser, /Video non trovato · Tocca per cercare/);
  assert.match(browser, /onRetryVideo = \{ retryMissingVideo\(seed\) \}/);
  assert.match(browser, /Modifier\.clickable\(onClick = onRetryVideo\)/);
  assert.match(browser, /fun retryMissingVideo\(seed: DiscogsVersionSeed\)/);
});

test('LAB63 manual retry is a bounded single-row query', () => {
  const start = browser.indexOf('fun retryMissingVideo(seed: DiscogsVersionSeed)');
  const end = browser.indexOf('fun play(seed: DiscogsVersionSeed)', start);
  const retry = browser.slice(start, end);
  assert.match(retry, /withTimeoutOrNull\(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS\)/);
  assert.match(retry, /resolveVideoChunk\(listOf\(current\.copy\(videoResolutionChecked = false\)\)\)/);
  assert.doesNotMatch(retry, /CloudMusicDiscovery\.discoverMemoryState|CoverDiscoverySources\.discover/);
});

test('cross-provider duplicates merge into one evidence coordinator', () => {
  assert.match(source, /internal fun sameCrossSourceVersion/);
  assert.match(source, /internal fun mergeCrossSourceEvidence/);
  assert.match(browser, /DiscogsVersionSource\.sameCrossSourceVersion\(existing, seed\)/);
  assert.match(browser, /DiscogsVersionSource\.mergeCrossSourceEvidence\(previous, seed\)/);
});

test('Originali keeps its final identity guard while later Cover gates remain stricter', () => {
  assert.match(browser, /fun modeAcceptsSeed\(seed: DiscogsVersionSeed\)/);
  assert.match(browser, /if \(mode == DiscogsDirectMode\.COVER\) \{/);
  assert.match(browser, /if \(aiTrusted\) return true/);
  assert.match(browser, /return independentServices >= 2 \|\|\s*\(seed\.workRelationConfirmed && sharedCredits\)/);
  assert.match(browser, /TitleMeaningResolver\.sameArtist\(seed\.artist, resolvedOriginalArtist\)/);
  assert.match(browser, /TitleMeaningResolver\.matchesBaseTitle/);
  assert.match(browser, /\.filter\(::modeAcceptsSeed\)/);
});

test('Cover consensus cannot rewrite the Originali artist anchor', () => {
  assert.match(browser, /mode == DiscogsDirectMode\.COVER && explicitArtistHint\.isNullOrBlank\(\)/);
});

test('LAB62 restores the current upstream Meld player, not Player buono LAB41', () => {
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS/);
  assert.doesNotMatch(player, /withTimeoutOrNull\(LAB07_FAST_LANE_TIMEOUT_MS\)/);
  assert.doesNotMatch(player, /tokenProvider = fastTokenProvider/);
  assert.match(player, /tokenProvider = tokenProvider/);
});

test('active LAB revisions remain in the same Android update family', () => {
  assert.match(uab, /UAB_UPDATE_FAMILY_ID="turbo01"/);
  assert.match(uab, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.ok(uab.includes('UAB_UPDATE_FAMILY_VISIBLE_NAME="Turbo LAB 02"'));
  const versionCode = Number(uab.match(/MUSICLAB_VERSION_CODE="(\d+)"/)?.[1] ?? 0);
  assert.ok(versionCode >= 1001);
  assert.match(uab, /UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"/);
});
