import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const text = p => fs.readFileSync(p,'utf8');
const shs = text('app/src/main/kotlin/com/metrolist/music/ui/component/SecondHandSongsCoverSource.kt');
const sources = text('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const gate = text('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');
const browser = text('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const suite = text('app/src/test/kotlin/com/metrolist/music/ui/component/CoverEvidenceRecoveryTest.kt');
const lookup = shs.split('private fun lookupFresh(')[1].split('private fun lookupByWorkTitle(')[0];
const fallback = shs.split('private fun lookupByWorkTitle(')[1].split('private fun fetchWorkVersions(')[0];

test('LAB42 SHS reads commonName and legacy artist name; restores valid performers',()=>{
  assert.match(shs,/obj\.opt\("performer"\)/);
  assert.match(shs,/optString\("commonName"\)/);
  assert.match(shs,/optString\("name"\)/);
  assert.match(shs,/val performer = performerFromReference\(obj\)/);
  assert.match(suite,/shsCommonNameIsRecognizedAsPerformer/);
  assert.match(suite,/shsLegacyNameRemainsSupported/);
});

test('LAB42 title-only SHS fallback must prove the selected performer is in the SAME work',()=>{
  assert.match(lookup,/lookupByWorkTitle\(title, artist\)/);
  assert.match(fallback,/result\.covers\.none \{ version ->/);
  assert.match(fallback,/TitleMeaningResolver\.sameArtist\(version\.artist, originalArtist\)/);
  assert.match(fallback,/TitleMeaningResolver\.matchesBaseTitle\(title, version\.title\)/);
  assert.match(fallback,/continue/);
});

test('LAB42 SHS remains bounded and no paid keys are required',()=>{
  assert.match(shs,/MAX_WORK_CANDIDATES = 4/);
  assert.match(shs,/MAX_SEARCH_CANDIDATES = 6/);
  assert.match(shs,/MAX_WORK_VERSION_DETAILS = 8/);
  assert.match(shs,/header\("Accept", "application\/json"\)/);
  assert.doesNotMatch(shs,/X-API-Key|Authorization: Bearer/);
});

test('LAB42 uses Apple composerName to add evidence for real musical works',()=>{
  assert.match(sources,/composerCreditsFromITunes\(item\.optString\("composerName"\)\)/);
  assert.match(sources,/internal fun composerCreditsFromITunes\(raw: String\)/);
  assert.match(sources,/DiscogsCredit\(name = it, role = "Composer"\)/);
  assert.match(suite,/itunesComposerIsEvidenceNotJustSongTitle/);
  assert.match(suite,/emptyComposerIsNotEvidence/);
});

test('LAB42 composer metadata alone cannot bypass original work credit comparison',()=>{
  assert.match(gate,/DiscogsVersionSource\.sharedWorkCreditNames\(seed, originalCredits\)/);
  assert.match(gate,/seed\.workRelationConfirmed/);
  assert.match(browser,/CoverSameWorkGate\.hasWorkIdentity\(/);
  const mode = browser.split('fun modeAcceptsSeed(seed: DiscogsVersionSeed): Boolean {')[1].split('fun isOriginalPerformerVersion(')[0];
  assert.ok(mode.indexOf('CoverSameWorkGate.hasWorkIdentity(') < mode.indexOf('if (titleOk) return true'));
});

test('LAB42 retains structured song-work relationships and no global confidence downgrade',()=>{
  assert.match(sources,/SecondHandSongsCoverSource\.lookup\(title, artist, forceRefresh = forceRefresh\)/);
  assert.match(sources,/MusicBrainzCoverSource\.lookup\(cleanTitle, cleanArtist, forceRefresh = forceRefresh\)/);
  assert.match(sources,/workRelationConfirmed = true/);
  assert.match(browser,/TURBO_RESULT_QUOTA = 50/);
  assert.match(browser,/session\.originalWorkCredits/);
});

test('LAB42 keeps approved layout and protected video/audio prioritization',()=>{
  assert.match(browser,/Modifier\.weight\(1f\)\.height\(56\.dp\)/);
  assert.match(browser,/playbackIsCritical\(\)/);
  assert.match(browser,/setCoverPerformanceLoad/);
  assert.match(browser,/videoPreparationPool\(\)/);
});

test('LAB42 refuses unrelated equal-title works even when MusicBrainz is not available',()=>{
  assert.match(lookup,/artistScore >= 0\.45/);
  assert.match(lookup,/TitleMeaningResolver\.sameArtist\(ref\.artist, artist\)/);
  assert.match(fallback,/originalArtist\.isNotBlank\(\)/);
  assert.match(gate,/Identità dell’opera non verificata/);
});
