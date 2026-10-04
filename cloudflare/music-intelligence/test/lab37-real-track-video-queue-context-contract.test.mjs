import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const uab = read('uab-project.env');

test('LAB37 never exposes a Discogs release candidate before exact tracklist verification', () => {
  assert.match(source, /verifyReleaseCandidates/);
  assert.match(source, /DiscogsClient\.getRelease/);
  assert.match(source, /seedsFromRelease/);
  assert.match(source, /internal fun isVerifiedDirectSeed/);
  assert.match(source, /seed\.track != null && seed\.confidenceScore > 0/);
  assert.match(source, /confidenceScore = 0/);
  assert.match(browser, /DiscogsVersionSource\.isVerifiedDirectSeed/);
});

test('LAB37 broadens Originali discovery but verifies the original performer at track level', () => {
  const pageStart = source.indexOf('suspend fun loadVersionPage');
  const pageEnd = source.indexOf('private suspend fun verifyReleaseCandidates', pageStart);
  const pageBlock = source.slice(pageStart, pageEnd);
  assert.match(pageBlock, /artist = null/);
  assert.match(pageBlock, /requiredTrackArtist =[\s\S]*DiscogsDirectMode\.ORIGINAL[\s\S]*originalArtist/);
  assert.match(source, /DiscogsDirectMode\.ORIGINAL ->[\s\S]*sameArtist\(seed\.artist, originalArtist\)/);
});

test('LAB37 foreign-version AI remains scout-only and requires a real Discogs track', () => {
  const start = source.indexOf('suspend fun loadReferenceSeeds');
  const end = source.indexOf('suspend fun enrichSeedMetadata', start);
  const block = source.slice(start, end);
  assert.match(block, /verifyReleaseCandidates/);
  assert.match(block, /requiredTrackArtist = referenceArtist/);
  assert.match(block, /traccia reale verificata su Discogs/);
});

test('LAB37 resolves videos in page-independent batches of ten', () => {
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 10/);
  assert.match(browser, /DIRECT_VIDEO_PARALLELISM = 3/);
  assert.match(browser, /resolveNextVideoBatch/);
  assert.match(browser, /scheduleVideoPreload/);
  assert.match(source, /videoResolutionChecked: Boolean = false/);
  assert.match(source, /markVideoUnavailable/);
  assert.doesNotMatch(browser, /LaunchedEffect\(listState, visibleResults\.map \{ it\.releaseId \}\)/);
});

test('LAB37 keeps rows stable while background evidence and video data arrive', () => {
  assert.match(browser, /"discogs_direct_\$\{mode\.name\}_\$\{seed\.fingerprint\}"/);
  assert.doesNotMatch(browser, /"discogs_direct_\$\{mode\.name\}_\$\{index\}_/);

  const videoStart = source.indexOf('internal fun markVideoResolved');
  const videoEnd = source.indexOf('private fun initialConfidence', videoStart);
  const videoBlock = source.slice(videoStart, videoEnd);
  assert.doesNotMatch(videoBlock, /confidenceScore\s*=/);

  const creditStart = source.indexOf('internal fun applySharedWorkCreditEvidence');
  const creditEnd = source.indexOf('internal fun markVideoResolved', creditStart);
  const creditBlock = source.slice(creditStart, creditEnd);
  assert.doesNotMatch(creditBlock, /confidenceScore\s*=/);
});

test('LAB37 preserves scroll anchor and replaces unrelated playback queue with Cover or Originali context', () => {
  assert.match(browser, /session\.listIndex = listState\.firstVisibleItemIndex/);
  assert.match(browser, /session\.listOffset = listState\.firstVisibleItemScrollOffset/);
  assert.match(browser, /ListQueue\(/);
  assert.match(browser, /connection\.playQueue\(/);
  assert.match(browser, /"Cover · \$title"/);
  assert.match(browser, /"Originali · \$title"/);
});

test('LAB37 explicitly reports unavailable video instead of leaving verification forever', () => {
  assert.match(browser, /seed\.videoResolutionChecked -> "Video non trovato"/);
  assert.match(browser, /else -> "Video in verifica…"/);
});

test('LAB37 APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB37"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB37"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab37"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB37"/);
});
