import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const gate=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt','utf8');
const sourceModel=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt','utf8');

test('LAB45: two distinct card menu actions have separate callbacks',()=>{
  assert.match(source,/text = \{ Text\("Cerca video"\) \}/);
  assert.match(source,/text = \{ Text\("Cambia cover"\) \}/);
  assert.doesNotMatch(source,/Cerca video \/ cambia cover/);
  assert.match(source,/onSearchVideo: \(\) -> Unit/);
  assert.match(source,/onRetryVideo: \(\) -> Unit/);
  assert.match(source,/onSearchVideo = \{ searchVideoForRecording\(seed\) \}/);
});

test('LAB45: video search must preserve singer and recording',()=>{
  const m=source.split('fun searchVideoForRecording(seed: DiscogsVersionSeed)')[1].split('fun manualSwapReady(')[0];
  assert.match(m,/lab21VerifyAlternative\(/);
  assert.match(m,/original\.copy\(resolvedVideoId = null, videoResolutionChecked = false\)/);
  assert.match(m,/resolvedVideoId = replacement\.resolvedVideoId/);
  assert.match(m,/it\.fingerprint == original\.fingerprint/);
  assert.doesNotMatch(m,/artist = replacement\.artist|trackTitle = replacement\.trackTitle/);
  assert.match(m,/hasPublishableVideo\(replacement\)/);
  assert.match(m,/replacement\.resolvedVideoId != original\.resolvedVideoId/);
});

test('LAB45: change-cover lane enforces different recording, no duplicates',()=>{
  const m=source.split('fun retryMissingVideo(seed: DiscogsVersionSeed)')[1].split('fun play(seed: DiscogsVersionSeed)')[0];
  assert.match(m,/DiscogsVersionSource\.recordingIdentityKey\(candidate\) !in occupied/);
  assert.match(m,/candidate\.fingerprint !in tried/);
  assert.match(m,/modeAcceptsSeed\(candidate\)/);
  assert.match(m,/candidate\.fingerprint != current\.fingerprint/);
  assert.match(m,/videoId in usedIds/);
  assert.match(m,/previousRoundRows \+ publishedCoverSnapshots \+ publishedOriginalSnapshots/);
});

test('LAB45: same video never repeats across historical rows',()=>{
  assert.match(source,/previousRoundRows \+ publishedOriginalSnapshots \+ publishedCoverSnapshots/);
  assert.match(source,/oldVideos = previousRoundRows\.mapNotNullTo/);
  assert.match(source,/it\.resolvedVideoId\?\.trim\(\)\?\.takeIf\(String::isNotBlank\) in oldVideos/);
});

test('LAB45: there is one search-more action at the list bottom',()=>{
  assert.match(source,/item\(key = "musiclab_turbo_cover_search_more_round_/);
  assert.match(source,/Text\("Cerca ancora · fino a 50 nuove cover"\)/);
  assert.match(source,/runSearch\(forceRefresh = true, appendRound = true\)/);
  assert.match(source,/activeCriteria != null && !loading && !sourceDiscoveryLoading/);
});

test('LAB45: each additional round uses the original 50-result research engine',()=>{
  assert.match(source,/appendRound: Boolean = false/);
  assert.match(source,/TURBO_RESULT_QUOTA = 50/);
  assert.match(source,/val appendVerifiedRound = appendRound && isTurbo/);
  assert.match(source,/if \(appendRound && !appendVerifiedRound\) return/);
  assert.match(source,/coverRoundNumber\+\+/);
  assert.match(source,/session\.coverRoundNumber = coverRoundNumber/);
});

test('LAB45: historical poster rows are retained and new rounds never reorder them',()=>{
  assert.match(source,/var previousRoundRows: List<DiscogsVersionSeed> = emptyList\(\)/);
  assert.match(source,/session\.previousRoundRows = previousRoundRows/);
  assert.match(source,/previousRoundRows = previousRoundRows \+ additional/);
  assert.match(source,/previousRoundRows\.filter \{/);
  assert.match(source,/publishedOriginalSnapshots\.filterNot\(::isHiddenForCurrentCover\)/);
  assert.match(source,/if \(!appendVerifiedRound\) listState\.scrollToItem\(0\)/);
});

test('LAB45: old recordings excluded from every fresh ranked pool',()=>{
  assert.match(source,/val oldRecordingKeys = previousRoundRows\.mapTo/);
  assert.match(source,/DiscogsVersionSource\.recordingIdentityKey\(it\) in oldRecordingKeys/);
  assert.match(source,/distinctBy\(DiscogsVersionSource::recordingIdentityKey\)/);
  assert.match(sourceModel,/internal fun recordingIdentityKey/);
  assert.match(sourceModel,/canonicalBaseTitle\(seed\.trackTitle\)/);
  assert.match(sourceModel,/canonicalArtist\(seed\.artist\)/);
});

test('LAB45: preserved old cards can still use original playback',()=>{
  assert.match(source,/val published = previousRoundRows \+ publishedOriginalSnapshots \+ publishedCoverSnapshots/);
  assert.match(source,/previousRoundRows\.firstOrNull \{ it\.fingerprint == seed\.fingerprint \}/);
  assert.match(source,/session\.playReadyVideoIds/);
});

test('LAB45: identity quality and audio safety preserved',()=>{
  assert.match(gate,/fun hasWorkIdentity/);
  assert.match(gate,/hasDocumentaryRelation/);
  assert.match(source,/CoverSameWorkGate\.hasWorkIdentity\(/);
  assert.match(source,/playbackIsCritical\(\)/);
  assert.match(source,/TURBO_VIDEO_COVERAGE_MAX_MS/);
});
