import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const env = fs.readFileSync('uab-project.env', 'utf8');
const section = (start, end) => {
  const a = browser.indexOf(start);
  const b = browser.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, `missing section boundary ${start}`);
  return browser.slice(a, b);
};

test('LAB61 begins every Cover page at exactly ten ranked slots', () => {
  assert.match(browser, /DIRECT_COVER_PAGE_SIZE = 10/);
  assert.match(browser, /var visibleLimit: Int = DIRECT_COVER_PAGE_SIZE/);
  const publishing = section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(publishing, /val pending = remainingCoverPool\(\)/);
  assert.match(publishing, /val group = pending\.take\(requested\)/);
  assert.doesNotMatch(publishing, /filter\(::hasVideoPreview\)/);
  assert.match(publishing, /publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
  const ranking = section('val rankingEnriched =', 'fun loadNextPage()');
  assert.match(ranking, /session\.rankingFrozen = true[\s\S]*publishReadyBatches\(\)/);
});

test('LAB61 publishes ten ranked songs even with only three known YouTube video IDs', () => {
  const ranked = Array.from({ length: 65 }, (_, i) => ({
    rank: 20 - Math.floor(i / 5),
    fingerprint: 'version-' + i,
    videoId: i === 0 || i === 3 || i === 7 ? 'video-' + i : null,
  }));
  const firstPage = ranked.slice(0, 5).concat(ranked.slice(5, 10));
  assert.equal(firstPage.length, 10);
  assert.equal(firstPage.filter(s => s.videoId).length, 3);
  assert.deepEqual(firstPage.map(s => s.fingerprint), ranked.slice(0, 10).map(s => s.fingerprint));
  assert.deepEqual(ranked.slice(10, 20).map(s => s.fingerprint), Array.from({length:10}, (_,i)=>'version-'+(i+10)));
});

test('LAB61 snapshot refreshes the same ranked card when video ID arrives', () => {
  const visible = section('val visibleTrueCovers =', 'val visibleResults =');
  assert.match(visible, /val currentById = publishPool\.associateBy \{ it\.fingerprint \}/);
  assert.match(visible, /publishedCoverSnapshots\.mapNotNull \{ currentById\[it\.fingerprint\] \}/);
  assert.match(visible, /if \(sortMode == DirectVersionSort\.RELEVANCE\) page/);
  assert.doesNotMatch(visible, /filter\(::hasVideoPreview\)/);
  const card = browser.slice(browser.indexOf('private fun DiscogsVersionCard('));
  assert.match(card, /model = stableArtworkUrl/);
  assert.match(browser, /https:\/\/i\.ytimg\.com\/vi\/\$it\/hqdefault\.jpg/);
  assert.doesNotMatch(card.slice(card.indexOf('if (showVideoPreview)'), card.indexOf('} else {', card.indexOf('if (showVideoPreview)'))), /seed\.coverUrl/);
});

test('LAB61 only discovers video IDs for visible Cover rows, regardless of publication state', () => {
  const pending = section('fun videoPreparationPool()', 'fun preparationReadyVideoCount()');
  assert.match(pending, /coverCandidatePool\(\)/);
  assert.doesNotMatch(pending, /remainingCoverPool\(\)/);
  assert.match(pending, /take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
  assert.match(pending, /it\.resolvedVideoId\.isNullOrBlank\(\) && !it\.videoResolutionChecked/);
  const verifier = section('fun scheduleDiscogsVerification()', 'fun pauseCoverBackgroundForPlayback()');
  assert.match(verifier, /if \(videoPreloadJob\?\.isActive == true\) return/);
  assert.match(verifier, /coverCandidatePool\(\)\.take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
});

test('LAB61 playback immediately hands known video to LAB41 native player', () => {
  const tap = section('suspend fun playResolvedContext(', 'suspend fun loadPage(');
  assert.match(tap, /connection\.playQueue\(/);
  assert.doesNotMatch(tap, /YouTube\.queue\(/);
  assert.doesNotMatch(tap, /getStreamUrl\(/);
  assert.doesNotMatch(tap, /withTimeoutOrNull\(900L\)/);
  const play = section('fun play(seed:', 'LaunchedEffect(');
  assert.match(play, /beginPlaybackPriorityBurst/);
  assert.match(play, /currentSeed\.copy\(videoResolutionChecked = false\)/);
});

test('LAB61 diagnostics do not describe an unverified stream as play-ready', () => {
  assert.doesNotMatch(browser, /Pubblicati play-ready:/);
  assert.doesNotMatch(browser, /Blocco corrente 5\+5:/);
  assert.match(browser, /Video identificati:/);
  assert.match(browser, /Video da associare:/);
});

test('LAB61 version 6101 updates family 01 without changing app identity', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="6401"/);
  assert.match(env, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});
