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

test('LAB57 keeps ten-result Cover requests but publishes them in play-ready 5+5 blocks', () => {
  assert.match(browser, /DIRECT_COVER_PAGE_SIZE = 10/);
  assert.match(browser, /DIRECT_VERSION_PAGE_SIZE = 20/);
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 5/);
  assert.match(browser, /pageSize = if \(mode == DiscogsDirectMode\.COVER\) DIRECT_COVER_PAGE_SIZE else DIRECT_VERSION_PAGE_SIZE/);
  assert.match(browser, /visibleMembershipPool[\s\S]*publishPool\.take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
  assert.match(browser, /visibleMembershipPool\.filter\(::isPlayReady\)/);
  assert.match(browser, /publishPool\.take\(publishedReadyLimit\.coerceAtMost\(visibleLimit\)\)/);
});

test('LAB57 prepares the frozen ranking in contiguous play-ready batches', () => {
  assert.match(browser, /fun videoPriorityPool/);
  assert.match(browser, /compareByDescending<DiscogsVersionSeed> \{ it\.confidenceScore \}/);
  assert.match(browser, /suspend fun publishReadyBatches\(\)/);
  assert.match(browser, /publishedReadyLimit \+ DIRECT_VIDEO_BATCH_SIZE/);
  assert.match(browser, /Blocco corrente 5\+5: \$\{readyPool\.size\}\/\$\{visibleMembershipPool\.size\} play-ready/);
});

test('LAB52 uses a wider source fetch so failed videos do not prevent a ten-video page', () => {
  assert.match(browser, /DIRECT_VERSION_SOURCE_FETCH_SIZE = 30/);
  assert.match(browser, /perPage = DIRECT_VERSION_SOURCE_FETCH_SIZE/);
  assert.match(browser, /readyVideoCount\(\)/);
});

test('LAB52 Cover card keeps resolved-video thumbnail as the Cover image without album-art fallback', () => {
  assert.match(browser, /showVideoPreview = true/);
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

test('LAB57 verifies an existing MusicLab/direct binding before the external resolver', () => {
  const resolverStart = browser.indexOf('suspend fun resolveVideoChunk');
  const resolverEnd = browser.indexOf('fun videoPriorityPool', resolverStart);
  const chunk = browser.slice(resolverStart, resolverEnd);
  const existingIndex = chunk.indexOf('val existingId = current.resolvedVideoId');
  const helperProbeIndex = chunk.indexOf('connection.service.getStreamUrl(candidate.id)');
  const existingVerifyIndex = chunk.indexOf('verified = verifyCandidateSong(existingSong', existingIndex);
  const externalIndex = chunk.indexOf('CompilationTrackResolver.resolveTrack(', existingIndex);
  assert.ok(existingIndex >= 0, 'existing MusicLab/direct binding lane missing');
  assert.ok(helperProbeIndex >= 0, 'play-ready helper must probe the real stream');
  assert.ok(existingVerifyIndex > existingIndex, 'existing binding must use the play-ready verifier');
  assert.ok(externalIndex > existingVerifyIndex, 'external resolver must run only after existing binding verification');
});
