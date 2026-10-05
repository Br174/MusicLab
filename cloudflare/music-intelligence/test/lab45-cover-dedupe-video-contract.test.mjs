import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const cover = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const title = read('app/src/main/kotlin/com/metrolist/music/ui/component/TitleMeaningResolver.kt');
const uab = read('uab-project.env');

test('LAB45 dedupes musical recordings and keeps original plus newest remaster', () => {
  assert.match(source, /groupBy\(::recordingFamilyKey\)/);
  assert.match(source, /firstOriginal = originals\.minWithOrNull\(EARLIEST_MUSICAL_PUBLICATION\)/);
  assert.match(source, /latestRemaster = remasters\.maxWithOrNull\(LATEST_MUSICAL_PUBLICATION\)/);
  assert.match(source, /REMASTER_REGEX/);
  assert.match(source, /RERECORD_REGEX/);
  assert.match(source, /Release\/upload dates of YouTube videos are never used/);
});

test('video uniqueness is local to the current search and losers retry', () => {
  assert.match(browser, /session\.usedVideoIds\.clear\(\)/);
  assert.match(browser, /session\.knownVideoBindings\.clear\(\)/);
  assert.match(browser, /normalizeSearchLocalVideoBindings/);
  assert.match(browser, /Flusso video già assegnato a un’altra versione in questa ricerca: cerco alternativa/);
  assert.match(browser, /videoResolutionChecked = false/);
  assert.doesNotMatch(browser, /rejectedKeys \+= .*resolvedVideoId/);
});

test('one video coordinator tries MusicLab internal evidence before external resolver', () => {
  const chunkStart = browser.indexOf('suspend fun resolveVideoChunk');
  const chunkEnd = browser.indexOf('suspend fun resolveNextVideoBatch', chunkStart);
  const chunk = browser.slice(chunkStart, chunkEnd);
  assert.ok(chunk.indexOf('knownMusicLabVideo') < chunk.indexOf('CompilationTrackResolver.resolveTrack'));
  assert.match(browser, /recordingIdentityKey/);
  assert.match(browser, /MusicLab interno/);
});

test('base-title fallback is conditional and preserves decorated first lane', () => {
  assert.match(title, /fun workAnchorTitle/);
  assert.match(title, /fun versionDescriptors/);
  assert.match(browser, /needsAnchorFallback/);
  assert.match(browser, /!workAnchor\.equals\(criteria\.title, ignoreCase = true\)/);
  assert.match(browser, /!hasExplicitOriginalEvidence \|\| weakCoverage/);
  assert.match(browser, /consensusOriginalArtist/);
});

test('LRCLIB date uses music-source evidence and never a video upload date', () => {
  assert.match(cover, /publicationYearByExactRecording/);
  assert.match(cover, /candidate\.year == null && "LRCLIB" in candidate\.sources/);
  assert.match(cover, /canonical\(candidate\.title\) \+ "\\|" \+ canonicalArtist\(candidate\.artist\)/);
  assert.match(cover, /Never substitute a YouTube upload date/);
});

test('Cover header supports swipe-down close without replacing the close button', () => {
  assert.match(browser, /detectVerticalDragGestures/);
  assert.match(browser, /closeSwipeDistance >= 110f/);
  assert.match(browser, /navController\.popBackStack\(\)/);
  assert.match(browser, /Text\("Chiudi"\)/);
});

test('Discogs public fallback is not contradicted by a token-only UI gate', () => {
  assert.doesNotMatch(browser, /enabled = !loading && discogsToken\.isNotBlank\(\)/);
  assert.doesNotMatch(browser, /initialTitle\.isNotBlank\(\) &&\s*discogsToken\.isNotBlank\(\)/);
});

test('LAB45 is an in-place update of active family 01', () => {
  assert.match(uab, /UAB_UPDATE_FAMILY_ID="01"/);
  assert.match(uab, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(uab, /UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"/);
  assert.match(uab, /UAB_UPDATE_FAMILY_VISIBLE_NAME="LAB 45 aggiornamento"/);
  assert.match(uab, /MUSICLAB_VERSION_CODE="4501"/);
});
