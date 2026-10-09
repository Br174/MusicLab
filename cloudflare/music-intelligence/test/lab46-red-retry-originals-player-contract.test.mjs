import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const source = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt', 'utf8');
const player = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt', 'utf8');
const uab = fs.readFileSync('uab-project.env', 'utf8');

test('failed red rows are appended after playable or pending rows', () => {
  assert.match(browser, /val \(failedVideo, usableOrPending\)/);
  assert.match(browser, /seed\.videoResolutionChecked && seed\.resolvedVideoId\.isNullOrBlank\(\)/);
  assert.match(browser, /return usableOrPending \+ failedVideo/);
});

test('LAB63 red label launches bounded retry with recording-scoped exclusions', () => {
  assert.match(browser, /Video non trovato · Tocca per cercare/);
  assert.match(browser, /fun retryMissingVideo/);
  assert.match(browser, /withTimeoutOrNull\(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS\)/);
  assert.match(browser, /CoverPlaybackMemory\.rejectedVideoIds\(context, current\.fingerprint\)/);
  assert.doesNotMatch(browser, /fastFirst = false/);
});

test('cross-provider duplicates use one merge coordinator', () => {
  assert.match(source, /sameCrossSourceVersion/);
  assert.match(source, /mergeCrossSourceEvidence/);
  assert.match(browser, /DiscogsVersionSource\.sameCrossSourceVersion/);
  assert.match(browser, /DiscogsVersionSource\.mergeCrossSourceEvidence/);
});

test('Originali fixes performer to the resolved original artist and reuses work identity', () => {
  assert.match(browser, /fun modeAcceptsSeed/);
  assert.match(browser, /TitleMeaningResolver\.sameArtist\(seed\.artist, resolvedOriginalArtist\)/);
  assert.match(browser, /TitleMeaningResolver\.matchesBaseTitle/);
  assert.match(browser, /mode == DiscogsDirectMode\.COVER && explicitArtistHint\.isNullOrBlank\(\)/);
});

test('LAB62 removes LAB41 alternate extraction lane and uses Meld resolver', () => {
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS/);
  assert.doesNotMatch(player, /fastExtractor\.extract/);
  assert.doesNotMatch(player, /fastStream \?: requireNotNull/);
  assert.doesNotMatch(player, /usesWebView = false/);
});

test('active LAB revision stays in update family 01', () => {
  assert.match(uab, /UAB_UPDATE_FAMILY_ID="01"/);
  assert.match(uab, /METROLIST_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  const versionCode = Number(uab.match(/MUSICLAB_VERSION_CODE="(\d+)"/)?.[1] ?? 0);
  assert.ok(versionCode >= 4601);
  assert.match(uab, /MusicLab LAB \d+[A-Z]? aggiornamento/);
});
