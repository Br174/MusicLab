import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const direct = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const guard = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');
const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const tests = read('app/src/test/kotlin/com/metrolist/music/ui/component/CoverSameWorkGateTest.kt');
const mode = direct.split('fun modeAcceptsSeed(seed: DiscogsVersionSeed): Boolean {')[1].split('fun isOriginalPerformerVersion(')[0];
const first = direct.split('fun sealPostersOnce() {')[1].split('fun sealSecondPostersOnce() {')[0];
const second = direct.split('fun sealSecondPostersOnce() {')[1].split('fun stopAtFullQuota()')[0];

test('LAB40: title match cannot bypass the same-work gate', () => {
  assert.match(mode, /CoverSameWorkGate\.hasWorkIdentity\(/);
  assert.ok(mode.indexOf('CoverSameWorkGate.hasWorkIdentity(') < mode.indexOf('if (titleOk) return true'));
  assert.match(mode, /lockedArtist\.ifBlank \{ resolvedOriginalArtist \}/);
});

test('LAB40: different singer with same title stays unverified until evidence', () => {
  assert.match(guard, /hasDocumentaryRelation\(seed: DiscogsVersionSeed\)/);
  assert.match(guard, /seed\.workRelationConfirmed/);
  assert.match(guard, /sameArtist\(seed\.artist, referenceArtist\)/);
  assert.match(guard, /sharedWorkCreditNames\(seed, originalCredits\)/);
  assert.ok(!guard.includes('seed.confidenceScore >='), 'arbitrary score is not identity proof');
  assert.match(tests, /homonymousSongsStaySeparate/);
  assert.match(tests, /numericCloudScoreIsNotProof/);
});

test('LAB40: Cloud archive and numeric scores do not assert work relation', () => {
  assert.ok((direct.match(/workRelationConfirmed = false/g) ?? []).length >= 2);
  assert.match(cloud, /expectedArtist: String = ""/);
  assert.match(cloud, /archiveKey\(item\.artist\) != archiveKey\(expectedArtist\)/);
  assert.match(cloud, /archiveKey\(item\.originalArtist\) != archiveKey\(expectedArtist\)/);
  assert.match(direct, /CloudMusicDiscovery\.recentArchiveSelection\([\s\S]*?lockedArtist,/);
});

test('LAB40: recurring query keeps performer identity', () => {
  assert.match(direct, /val repeatSameWork =[\s\S]*?activeCriteria == criteria/);
  assert.match(direct, /TitleMeaningResolver\.sameArtist\([\s\S]*?lockedArtist, resolvedOriginalArtist/);
});

test('LAB40: first verified posters need not wait for ten candidates', () => {
  assert.match(first, /if \(initialOriginals\.isEmpty\(\) && initialCovers\.isEmpty\(\)\) return/);
  assert.match(first, /elapsed < TURBO_FIRST_POSTER_DEADLINE_MS/);
  assert.match(first, /TURBO_MIN_EARLY_POSTER_BLOCK/);
  assert.match(second, /committedCount == 0 && publishedOriginalSnapshots\.isEmpty\(\)/);
  assert.match(second, /CoverLab53Publication\.fill\(/);
});

test('LAB40: coalesce provider callbacks before ranked UI state changes', () => {
  assert.match(direct, /val providerBatch = buildList \{/);
  assert.match(direct, /streamingCandidates\.tryReceive\(\)\.getOrNull\(\)/);
  assert.match(direct, /providerBatch\.asSequence\(\)/);
  assert.match(direct, /withContext\(Dispatchers\.Default\)/);
});

test('LAB40: recover old Cloud image links via TLS and existing image cache', () => {
  assert.match(direct, /seed\.coverUrl\?\.replaceFirst\(Regex\("\^http:"/);
  assert.match(direct, /memoryCachePolicy\(CachePolicy\.ENABLED\)/);
  assert.match(direct, /diskCachePolicy\(CachePolicy\.ENABLED\)/);
  assert.match(direct, /seed\.resolvedVideoId\?\.trim\(\)/);
});

test('LAB40: the player, UI geometry and signed update family remain protected', () => {
  assert.match(direct, /backgroundWorkBlocked\(\)/);
  assert.match(direct, /playbackIsCritical\(\)/);
  assert.match(direct, /TURBO_RESULT_QUOTA = 50/);
  assert.match(direct, /Modifier\.weight\(1f\)\.height\(56\.dp\)/);
});
