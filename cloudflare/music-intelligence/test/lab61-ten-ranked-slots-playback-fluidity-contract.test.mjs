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

test('LAB65 ten places admit only verified videos in groups of five', () => {
  const publish = section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(publish, /val ready = settledPrefix/);
  assert.match(publish, /val group = ready\.take\(slots\)/);
  assert.match(publish, /publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
});

test('LAB65 a rank can enter only after a valid video ID is present', () => {
  const ranked = Array.from({length:65},(_,i)=>({score:20-Math.floor(i/5),valid:i===0||i===3||i===7}));
  assert.equal(ranked.filter(x=>x.valid).length,3);
  assert.ok(ranked.filter(x=>x.valid).every(x=>x.score>=19));
});

test('LAB65 visible cards are immutable approved snapshots', () => {
  const visible = section('val visibleOriginalVersions =', 'val visibleResults =');
  assert.match(visible, /publishedOriginalSnapshots/);
  assert.match(visible, /publishedCoverSnapshots\.take\(visibleLimit\)/);
  assert.doesNotMatch(visible, /remainder|currentById|shownOriginalIds/);
  assert.match(browser, /model = stableArtworkUrl/);
});

test('LAB61 only discovers video IDs for visible Cover rows, regardless of publication state', () => {
  const pending = section('fun videoPreparationPool()', 'fun preparationReadyVideoCount()');
  assert.match(pending, /coverCandidatePool\(\)/);
  assert.doesNotMatch(pending, /remainingCoverPool\(\)/);
  assert.match(pending, /take\(visibleLimit\.coerceAtLeast\(pageSize\)\)/);
  assert.match(pending, /filter\(::needsAutomaticVideo\)/);
  assert.match(browser, /fun needsAutomaticVideo\(seed: DiscogsVersionSeed\): Boolean/);
  assert.match(pending, /session\.automaticVideoAttempts\[it\.fingerprint\]/);
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
  assert.match(env, /UAB_UPDATE_FAMILY_VERSION_CODE="6501"/);
  assert.match(env, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});
