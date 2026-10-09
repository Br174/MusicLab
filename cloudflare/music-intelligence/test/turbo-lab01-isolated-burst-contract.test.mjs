import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const read = name => fs.readFileSync(name, 'utf8');
const browser=read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const cloud=read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const scout=read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const env=read('uab-project.env');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('Turbo LAB01 is a separate Android identity, not an update to LAB68',()=>{
  assert.ok(env.includes('UAB_UPDATE_FAMILY_APPLICATION_ID="it.verlezza.musiclab.turbo01"'));
  assert.ok(env.includes('export METROLIST_APPLICATION_ID="it.verlezza.musiclab.turbo01"'));
  assert.ok(env.includes('export METROLIST_APP_NAME="MusicLab Turbo LAB 02"'));
  assert.ok(env.includes('UAB_UPDATE_FAMILY_ID="turbo01"'));
  assert.ok(env.includes('UAB_UPDATE_FAMILY_VERSION_CODE="1002"'));
  assert.ok(!env.includes('it.verlezza.musiclab.labupdate01'));
});

test('Turbo read-only Cloud does not mutate shared editorial or video bindings',()=>{
  assert.ok(cloud.includes('BuildConfig.APPLICATION_ID == "it.verlezza.musiclab.turbo01"'));
  const editorial=cloud.slice(cloud.indexOf('suspend fun saveBrainDecision('),cloud.indexOf('suspend fun savePlaybackBinding('));
  const binding=cloud.slice(cloud.indexOf('suspend fun savePlaybackBinding('),cloud.indexOf('suspend fun searchArchive('));
  assert.ok(editorial.includes('if (turboReadOnly) return@withContext false'));
  assert.ok(binding.includes('if (turboReadOnly) return@withContext false'));
  assert.ok(scout.includes('BuildConfig.APPLICATION_ID == "it.verlezza.musiclab.turbo01"'));
  assert.ok(scout.includes('Turbo: Cloud editoriale in sola lettura'));
});

test('Turbo uses title-based Cloud archive directly without requiring artist',()=>{
  assert.ok(browser.includes('val turboArchiveDeferred = if (isTurbo && mode == DiscogsDirectMode.COVER)'));
  assert.ok(browser.includes('CloudMusicDiscovery.searchArchive('));
  assert.ok(browser.includes('query = criteria.title'));
  assert.ok(browser.includes('limit = 100'));
  assert.ok(browser.includes('TURBO_ARCHIVE_TIMEOUT_MS = 4_500L'));
  assert.ok(browser.includes('TitleMeaningResolver.matchesBaseTitle(criteria.title, anchor)'));
  assert.ok(browser.includes('val archiveSeeds = turboArchive.mapNotNull'));
  assert.ok(browser.includes('val turboArchive = turboArchiveDeferred?.await().orEmpty()'));
  assert.ok(browser.indexOf('val turboArchive = turboArchiveDeferred?.await().orEmpty()') <
      browser.indexOf('val firstPageLoaded ='));
});

test('Turbo burst targets at most 50 ranked VIDEO recordings and preserves ten-row view',()=>{
  assert.ok(browser.includes('TURBO_RESULT_QUOTA = 50'));
  assert.ok(browser.includes('TURBO_MAX_RANK_CANDIDATES = 100'));
  assert.ok(browser.includes('DIRECT_COVER_PAGE_SIZE = 10'));
  assert.ok(browser.includes('if (isTurbo) TURBO_RESULT_QUOTA else visibleLimit'));
  assert.ok(browser.includes('return coverCandidatePool()'));
  assert.ok(browser.includes('filter(::needsAutomaticVideo)'));
  assert.ok(browser.includes('filter(::hasPublishableVideo)'));
  assert.ok(browser.includes('publishedCoverSnapshots = publishedCoverSnapshots + group'));
  assert.ok(browser.includes('publishedCoverSnapshots.take(visibleLimit)'));
});

test('Turbo bursts 4 metadata checks when idle but protects playback and never warms streams',()=>{
  assert.ok(browser.includes('} else if (isTurbo) {\n                                    4'));
  assert.ok(browser.includes('batch.chunked(if (isTurbo && playerConnection?.isEffectivelyPlaying?.value != true) 4 else DIRECT_VIDEO_PARALLELISM)'));
  assert.ok(browser.includes('DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1'));
  assert.ok(browser.includes('if (backgroundWorkBlocked()) return@launch'));
  assert.ok(browser.includes('if (backgroundWorkBlocked() ||'));
  assert.ok(!browser.includes('YouTube.player('));
});

test('Turbo stops source jobs on quota or deadline, no automatic pagination after completion',()=>{
  assert.ok(browser.includes('TURBO_BURST_DEADLINE_MS = 40_000L'));
  assert.ok(browser.includes('publishedCoverSnapshots.size >= TURBO_RESULT_QUOTA'));
  assert.ok(browser.includes('session.turboFinished = true'));
  assert.ok(browser.includes('verificationJob?.cancel()'));
  assert.ok(browser.includes('paginationJob?.cancel()'));
  assert.ok(browser.includes('searchJob?.cancel()'));
  assert.ok(browser.includes('CloudMusicDiscovery.cancelArchiveRequests()'));
  assert.ok(browser.includes('if (isTurbo && session.turboFinished) return'));
  assert.ok(browser.includes('if (isTurbo && repeatSameWork && session.turboFinished)'));
  assert.ok(browser.includes('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            if (visibleLimit < TURBO_RESULT_QUOTA'));
  assert.ok(browser.includes('!isTurbo &&\n            publishPool.size < visibleLimit + pageSize'));
});

test('Turbo actively cancels slow discovery and never blocks on the second work-anchor crawl',()=>{
  assert.ok(browser.includes('withTimeoutOrNull(4_500L) { firstPageDeferred.await() }'));
  assert.ok(browser.includes('withTimeoutOrNull(5_000L)'));
  assert.ok(browser.includes('if (!externalDeferred.isCompleted) externalDeferred.cancel()'));
  assert.ok(browser.includes('if (!firstPageDeferred.isCompleted) firstPageDeferred.cancel()'));
  assert.ok(browser.includes('if (needsAnchorFallback && !isTurbo)'));
});

test('Turbo protects byte-identical approved original player',()=>{
  const blob=Buffer.concat([Buffer.from('blob '+player.length+'\\0'),player]);
  // Use actual NUL Git object header, not two literal backslash bytes.
  const gitBlob=Buffer.concat([Buffer.from('blob '+player.length),Buffer.from([0]),player]);
  const hash=createHash('sha1').update(gitBlob).digest('hex');
  assert.equal(hash,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.ok(browser.includes('ContentScale.Crop'));
});
