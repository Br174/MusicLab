import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const prefix='app/src/main/kotlin/com/metrolist/music/';
const read=p=>fs.readFileSync(prefix+p,'utf8');
const cover=read('ui/component/DiscogsDirectVersionBrowser.kt');
const coverEntry=read('ui/component/CoverSearchScreen.kt');
const compilation=read('ui/component/CompilationScreen.kt');
const resolver=read('discogs/CompilationTrackResolver.kt');
const service=read('playback/MusicService.kt');
const policy=read('playback/PlaybackSprintPolicy.kt');

test('R60: actual Turbo Cover entry uses protected direct player, not deprecated AI screen',()=>{
  assert.match(coverEntry,/DiscogsDirectVersionBrowser\(\s*mode = DiscogsDirectMode\.COVER/);
  const tap=cover.slice(cover.indexOf('fun play(seed: DiscogsVersionSeed)'),
    cover.indexOf('fun swipeCover(',cover.indexOf('fun play(seed: DiscogsVersionSeed)')));
  assert.match(tap,/pauseCoverBackgroundForPlayback\(\)/);
  assert.match(tap,/beginPlaybackPriorityBurst\("musiclab-version-tap"\)/);
  assert.match(cover,/COVER_TAP_TO_PLAYER_MS=/);
  assert.match(cover,/connection\.playNow\(selectedItem\)/);
  assert.match(cover,/session\.preparedVideoSongs\[selectedId\]/);
  assert.doesNotMatch(tap,/Thread\.sleep|runBlocking/);
});

test('R60: Cover result snapshots and background resume remain intact',()=>{
  assert.match(cover,/fun pauseCoverBackgroundForPlayback\(\)/);
  assert.match(cover,/fun resumeCoverBackgroundAfterPlaybackBurst\(\)/);
  assert.match(cover,/session\.previousRoundRows/);
  assert.match(cover,/publishedCoverSnapshots/);
  assert.match(cover,/TURBO_VIDEO_COVERAGE_MAX_MS/);
  assert.match(cover,/playerConnection\?\.service\?\.isCoverAudioAtRisk\(\)/);
});

test('R60: Compilation starts 3 concurrent bounded sources without sacrificing rare/deep fallback',()=>{
  const rapid=resolver.slice(resolver.indexOf('suspend fun resolveTrackForPlayback('),
    resolver.indexOf('suspend fun resolveTrack(',resolver.indexOf('suspend fun resolveTrackForPlayback(')));
  assert.match(rapid,/val winner = CompletableDeferred<CompilationResolvedTrack>/);
  assert.match(rapid,/fastMusicLabRace\(track, query, emptySet\(\), turboWindow = true\)/);
  assert.match(rapid,/YouTubeWebSearch\.search\(query\)/);
  assert.match(rapid,/bestDiscogsTrackVideo\(track, discogsVideos, emptySet\(\)\)/);
  assert.match(rapid,/withTimeoutOrNull\(4_800L\)/);
  assert.match(rapid,/jobs\.forEach \{ it\.cancel\(\) \}/);
  assert.match(resolver,/private fun bestSong\(/);
  assert.match(resolver,/\.filter \{ isHardCompatible\(track, it\) \}/);
  assert.match(resolver,/suspend fun resolveTrack\(/);
});

test('R60: immediate Compilation selected-track tap uses rapid race then retained deep source only on a miss',()=>{
  const tap=compilation.slice(compilation.indexOf('fun playTrack('),compilation.indexOf('fun saveCompilation('));
  assert.match(tap,/resolveTrack\(index, current, playbackFast = true\)/);
  assert.match(tap,/if \(found == null\)/);
  assert.match(tap,/resolveTrack\(index, current, skipNativeLookup = true\)/);
  assert.match(tap,/COMPILATION_LOOKUP_MS=/);
  assert.match(tap,/MusicLabSectionPlayback\.select\(/);
  assert.match(tap,/if \(selectedTrackIndex != index \|\| detail\?\.id != current\.id\) return@launch/);
});

test('R60: stream singleflight publishes expiring URL while lock is owned, avoiding repeated extraction',()=>{
  const source=service.slice(service.indexOf('private fun createDataSourceFactory('),
    service.indexOf('private fun createMediaSourceFactory('));
  assert.match(service,/private val streamResolutionGates = Array\(48\) \{ Mutex\(\) \}/);
  assert.match(source,/gate\.lock\(\)/);
  assert.match(source,/finally \{\s*gate\.unlock\(\)/);
  assert.match(source,/val previouslyResolved =/);
  assert.match(source,/songUrlCache\[mediaId\]/);
  assert.match(source,/R60_STREAM_JOIN/);
  const block=source.slice(source.indexOf('gate.lock()'),source.indexOf('singleFlight.first?.let'));
  assert.ok(block.indexOf('songUrlCache.put(') < block.indexOf('gate.unlock()'));
  assert.match(block,/expectedGeneration = cacheGeneration/);
  assert.match(source,/val playbackData =/);
  assert.match(source,/STREAM_RESOLVE/);
});

test('R60: keep R59 no 1-second restart protections, pinned videos, and start/rebuffer safety',()=>{
  assert.match(service,/R59_STALE_QUEUE_RESTORE_IGNORED/);
  assert.match(service,/R59_RESTORE_NO_DELAYED_SEEK/);
  assert.match(service,/PlaybackSprintPolicy\.mayApplyAsyncShuffle/);
  assert.match(policy,/fun mayRestorePersistedQueue\(/);
  assert.match(service,/MusicLabManualVersionStore\.apply/);
  assert.match(service,/PLAYBACK_START_BUFFER_MS = 1_600/);
  assert.match(service,/PLAYBACK_REBUFFER_MS = 8_000/);
  assert.match(service,/R58_IS_PLAYING/);
  assert.match(service,/R59_LOCAL_AUDIO_FASTPATH/);
});
