import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
  'utf8',
);
const sources = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt',
  'utf8',
);
const versionSource = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt',
  'utf8',
);
const musicBrainz = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/MusicBrainzCoverSource.kt',
  'utf8',
);
const service = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt',
  'utf8',
);

test('LAB57 resolves the musical Work before recording fallback and keeps work metadata', () => {
  const lookupFresh = musicBrainz.indexOf('private fun lookupFresh');
  const workFirst = musicBrainz.indexOf('lookupByWorkTitle(', lookupFresh);
  const recordingFallback = musicBrainz.indexOf('val rawQueries = buildList', lookupFresh);
  assert.ok(workFirst > lookupFresh);
  assert.ok(recordingFallback > workFirst);
  assert.match(musicBrainz, /data class MusicBrainzWorkAnchor/);
  assert.match(musicBrainz, /val originalYear: Int\?/);
  assert.match(musicBrainz, /val credits: List<MusicBrainzWorkCredit>/);
  assert.match(musicBrainz, /internal fun parseWorkCredits/);
  assert.match(musicBrainz, /MAX_BROWSED_RECORDINGS = 300/);
});

test('LAB57 bounds source fanout and prevents one service from self-awarding a top score', () => {
  assert.match(sources, /val sourceGate = Semaphore\(4\)/);
  assert.match(sources, /sourceGate\.withPermit/);
  assert.match(sources, /seed\.relationRole == CoverInfoRelationRole\.INITIAL -> 8/);
  assert.match(sources, /seed\.directRelation -> 7/);
  assert.match(sources, /if \(coverInfoOnly\) score = score\.coerceAtMost\(13\)/);
  assert.match(sources, /if \(candidate\.year == null\) score = score\.coerceAtMost\(11\)/);
});

test('LAB57 keeps AI foreign trust but traditional changed-title services require extra proof', () => {
  assert.match(browser, /if \(aiTrusted\) return true/);
  assert.match(browser, /return independentServices >= 2 \|\|\s*\(seed\.workRelationConfirmed && sharedCredits\)/);
  assert.match(sources, /val crossVerifiedRelation =\s*candidate\.workRelationConfirmed && independentSourceConsensus/);
  assert.match(sources, /val sharedCreditEvidence =/);
  assert.match(browser, /if \(!chronologyOk && !isApproved\(seed\)\) return false/);
});

test('LAB57 freezes ranking before video work and appends later pages without reshuffling', () => {
  assert.match(browser, /var rankingFrozen: Boolean = false/);
  assert.match(browser, /session\.rankingFrozen = true/);
  assert.match(browser, /if \(rankingFrozen\) syncStableOrder\(\) else rebuildStableOrder\(\)/);
  assert.match(browser, /if \(!rankingFrozen && sortMode == DirectVersionSort\.RELEVANCE/);
  assert.match(versionSource, /fun certifyForFrozenRanking/);
  assert.match(versionSource, /Data musicale non reperita: candidato mantenuto con affidabilità limitata/);
});

test('LAB57 publishes Cover rows 5+5 only after metadata and real stream readiness', () => {
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 5/);
  assert.match(browser, /val playReadyVideoIds: MutableSet<String>/);
  assert.match(browser, /suspend fun publishReadyBatches\(\)/);
  assert.match(browser, /publishedReadyLimit \+ DIRECT_VIDEO_BATCH_SIZE/);
  assert.match(browser, /connection\.service\.getStreamUrl\(candidate\.id\)/);
  assert.match(browser, /session\.playReadyVideoIds \+= candidate\.id/);
  assert.match(browser, /publishPool\.take\(publishedReadyLimit\.coerceAtMost\(visibleLimit\)\)/);
});

test('LAB57 stops Cover-owned work on exit and lowers audio quality only while Cover is heavy', () => {
  assert.match(browser, /setCoverPerformanceLoad\(active = false, heavy = false\)/);
  assert.match(browser, /playbackLaunchJob\?\.cancel\(\)/);
  assert.match(browser, /searchJob\?\.cancel\(\)/);
  assert.match(browser, /videoPreloadJob\?\.cancel\(\)/);
  assert.match(service, /fun setCoverPerformanceLoad\(active: Boolean, heavy: Boolean\)/);
  assert.match(service, /if \(coverSectionActive && coverHeavyLoad\)/);
  assert.match(service, /AudioQuality\.LOW/);
  assert.match(service, /audioQuality = effectiveAudioQuality\(\)/);
});

test('LAB57 keeps Cover cards at fixed height while full metadata stays in Details', () => {
  assert.match(browser, /\.height\(if \(showVideoPreview\) 184\.dp else 104\.dp\)/);
  assert.match(browser, /Text\("Data pubblicazione: \$publicationDate"\)/);
  assert.match(browser, /Text\("Autore: \$authors"\)/);
  assert.match(browser, /Text\("Compositore: \$composers"\)/);
});
