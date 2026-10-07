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
const coverSources = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt',
  'utf8',
);

test('LAB52 Cover publishes ten video-ready rows per page without changing Originali page size', () => {
  assert.match(browser, /DIRECT_COVER_PAGE_SIZE = 10/);
  assert.match(browser, /DIRECT_VERSION_PAGE_SIZE = 20/);
  assert.match(browser, /pageSize = if \(mode == DiscogsDirectMode\.COVER\) DIRECT_COVER_PAGE_SIZE else DIRECT_VERSION_PAGE_SIZE/);
  assert.match(browser, /navigablePool\.drop\(resultPageStart\)\.take\(pageSize\)/);
  assert.match(browser, /resultPagePool\.filter \{ !it\.resolvedVideoId\.isNullOrBlank\(\) \}/);
  assert.match(browser, /if \(mode == DiscogsDirectMode\.COVER\) readyPool else readyPool\.take\(visibleLimit\)/);
});

test('LAB52 prepares video candidates by evidence score from 20 down to 1', () => {
  assert.match(browser, /fun videoPriorityPool/);
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
  assert.match(browser, /val missingReady = \(targetReady - currentPageReadyVideoCount\(\)\)/);
  assert.match(browser, /Preparo i video della pagina: \$\{readyPool\.size\}\/\$\{resultPagePool\.size\} pronti/);
});

test('LAB52 uses a wider source fetch so failed videos do not prevent a ten-video page', () => {
  assert.match(browser, /DIRECT_VERSION_SOURCE_FETCH_SIZE = 30/);
  assert.match(browser, /perPage = DIRECT_VERSION_SOURCE_FETCH_SIZE/);
  assert.match(browser, /readyVideoCount\(\)/);
});

test('LAB52 Cover card keeps resolved-video thumbnail as the Cover image without album-art fallback', () => {
  assert.match(browser, /showVideoPreview = mode == DiscogsDirectMode\.COVER/);
  assert.match(browser, /https:\/\/i\.ytimg\.com\/vi\/\$it\/hqdefault\.jpg/);
  assert.match(browser, /contentDescription = seed\.resolvedVideoTitle \?: "Video cover"/);
  const coverPreviewStart = browser.indexOf('if (showVideoPreview) {');
  const coverPreviewEnd = browser.indexOf('} else {', coverPreviewStart);
  const coverPreview = browser.slice(coverPreviewStart, coverPreviewEnd);
  assert.doesNotMatch(coverPreview, /model = seed\.coverUrl/);
});

test('LAB52 preserves COVER.INFO direct video bindings and explicit source priority', () => {
  assert.match(coverSources, /playbackVideoId = seed\.playbackVideoId/);
  assert.match(coverSources, /playbackVideoSource = seed\.playbackVideoId\?\.let \{ "COVER\.INFO" \}/);
  assert.match(versionSource, /fun directVideoSourcePriority/);
  assert.match(versionSource, /"cover\.info" in source -> 40/);
  assert.match(versionSource, /"youtube music" in source -> 20/);
  assert.match(versionSource, /"youtube" in source -> 10/);
});

test('LAB52 keeps MusicLab known-video lane ahead of external resolver', () => {
  const internalIndex = browser.indexOf('knownMusicLabVideo(seed, track)');
  const externalIndex = browser.indexOf('CompilationTrackResolver.resolveTrack(');
  assert.ok(internalIndex >= 0, 'MusicLab internal video lane missing');
  assert.ok(externalIndex > internalIndex, 'external resolver must run after MusicLab internal video lane');
});
