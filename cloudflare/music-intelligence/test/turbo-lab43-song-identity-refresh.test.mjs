import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const direct = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const sources = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const mb = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicBrainzCoverSource.kt');
const shs = read('app/src/main/kotlin/com/metrolist/music/ui/component/SecondHandSongsCoverSource.kt');
const gate = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');

test('LAB43: cloud approvals cannot grant musical work identity', () => {
  assert.match(direct, /manuallyApproved = false,\s*\/\/ LAB43/);
  assert.match(direct, /AiBrainDecisionStatus\.APPROVED -> 7/);
  assert.doesNotMatch(direct, /session\.approvedKeys\.addAll\(\s*memoryVersions/);
  assert.match(gate, /seed\.workRelationConfirmed/);
});

test('LAB43: local editorial decisions are scoped to song AND original singer', () => {
  const scoped = /TitleMeaningResolver\.workAnchorTitle\(title\) \+ "\|" \+\s*TitleMeaningResolver\.canonical\(lockedArtist\.ifBlank \{ resolvedOriginalArtist \}\)/g;
  assert.equal((direct.match(scoped) || []).length,2);
  assert.match(direct, /fun localEditorialDecision\(seed: DiscogsVersionSeed\)/);
  assert.match(direct, /CoverPlaybackMemory\.saveEditorialDecision/);
});

test('LAB43: stale frozen snapshot entries must also pass the same work gate', () => {
  assert.match(direct, /fun isHiddenForCurrentCover\(seed: DiscogsVersionSeed\): Boolean =\s*mode == DiscogsDirectMode\.COVER/);
  assert.match(direct, /if \(isTurbo\) !eligibleCoverForPublication\(seed\)/);
  assert.match(direct, /!eligibleCoverForPublication\(seed\)/);
  assert.match(direct, /publishedCoverSnapshots\s*\.filterNot\(::isHiddenForCurrentCover\)/);
  assert.match(direct, /fun modeAcceptsSeed\(seed: DiscogsVersionSeed\)/);
  assert.match(direct, /CoverSameWorkGate\.hasWorkIdentity\(/);
});

test('LAB43: same-title cloud archive cannot replace an explicitly locked singer for memory query', () => {
  assert.match(direct,/val memoryArtist = lockedArtist\.ifBlank \{/);
  assert.match(direct,/explicitArtistHint\?\.takeIf\(String::isNotBlank\)/);
  assert.doesNotMatch(direct,/val memoryArtist = archiveArtist\?\.takeIf\(String::isNotBlank\)/);
});

test('LAB43: explicit retry is accessible from existing three-dots menu', () => {
  assert.match(direct, /text = \{ Text\("Ripeti ricerca"\) \}/);
  assert.match(direct, /runSearch\(forceRefresh = true\)/);
  assert.match(direct, /enabled = !loading/);
  assert.match(direct, /fun runSearch\([\s\S]*?forceRefresh: Boolean = false/);
});

test('LAB43: a fully linked video does NOT block explicit re-search', () => {
  assert.match(direct, /if \(!forceRefresh && isTurbo && repeatSameWork && session\.turboFinished/);
  assert.match(direct,/val retainedResults = if \(appendVerifiedRound \|\| \(repeatSameWork && !forceRefresh\)\)/);
  assert.match(direct,/val retainedOrder = if \(appendVerifiedRound \|\| \(repeatSameWork && !forceRefresh\)\)/);
  assert.match(direct,/searchJob\?\.cancel\(\)/);
});

test('LAB43: all documentary network caches permit force refresh, with normal caching unchanged', () => {
  assert.match(sources,/forceRefresh: Boolean = false/);
  assert.match(sources,/MusicBrainzCoverSource\.lookup\(cleanTitle, cleanArtist, forceRefresh = forceRefresh\)/);
  assert.match(sources,/SecondHandSongsCoverSource\.lookup\(title, artist, forceRefresh = forceRefresh\)/);
  for(const body of [mb,shs]){
    assert.match(body,/forceRefresh: Boolean = false/);
    assert.match(body,/if \(!forceRefresh\) cache\[key\]\?\.let/);
  }
});

test('LAB43: selected search triggers refresh but initial launch preserves cache', () => {
  assert.match(direct,/if \(!loading\) runSearch\(forceRefresh = true\)/);
  assert.match(direct,/onClick = \{ runSearch\(forceRefresh = true\) \}/);
  assert.match(direct,/if \(!session\.initialized && initialTitle\.isNotBlank\(\)\) \{\s*runSearch\(\)/);
});

test('LAB43: no audio engine, cover card, category or album visuals replaced',()=>{
  assert.match(direct,/Modifier\.weight\(1f\)\.height\(56\.dp\)/);
  assert.match(direct,/setCoverPerformanceLoad\(/);
  assert.match(direct,/TURBO_RESULT_QUOTA = 50/);
  assert.match(direct,/playbackIsCritical\(\)/);
});

test('LAB43: cached video metadata is not assumed to certify an unrelated work',()=>{
  assert.match(direct,/hasPublishableVideo\(seed: DiscogsVersionSeed\)/);
  assert.match(direct,/else !modeAcceptsSeed\(seed\)/);
  assert.match(direct,/CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
  assert.match(gate,/Identità dell’opera non verificata/);
});
