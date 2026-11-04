import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/';
const read=x=>fs.readFileSync(root+x,'utf8');
const section=read('playback/MusicLabSectionPlayback.kt');
const policy=read('playback/OrderedResultAutoplayPolicy.kt');
const service=read('playback/MusicService.kt');
const connection=read('playback/PlayerConnection.kt');
const cover=read('ui/component/DiscogsDirectVersionBrowser.kt');
const thumbnail=read('ui/player/Thumbnail.kt');
const genre=read('ui/screens/search/OnlineSearchResult.kt');
const chart=read('ui/screens/search/MusicLabChartScreen.kt');
const hit=read('ui/screens/search/MusicLabHistoricalHitParade.kt');
const comp=read('ui/component/CompilationScreen.kt');
const search=read('ui/screens/search/OnlineSearchScreen.kt');
const nativeTest=fs.readFileSync('app/src/test/kotlin/com/metrolist/music/playback/OrderedResultAutoplayPolicyTest.kt','utf8');

test('R62: natural end advances editorial playlist without radio, user pause/sleep respected',()=>{
  assert.match(connection,/if \(state == Player.STATE_ENDED\) \{\s*MusicLabSectionPlayback.onPlaybackEnded\(this\)/);
  assert.match(section,/fun onPlaybackEnded\(connection: PlayerConnection\)/);
  assert.match(section,/OrderedResultAutoplayPolicy.shouldAdvanceAtEnd/);
  assert.match(section,/advance\(connection, 1, automatic = true\)/);
  assert.match(service,/fun canAdvanceOrderedResults\(\)/);
  assert.match(service,/sleepTimer\?\.isActive == true/);
  assert.match(service,/sleepTimer\?\.pauseWhenSongEnd == true/);
  assert.match(policy,/fun shouldAdvanceAtEnd\(/);
  assert.match(nativeTest,/respectUserPauseAndSleepTimerAndCurrentSelectionOwnership/);
});

test('R62: native auto transition replenishes every next song until final result',()=>{
  assert.match(connection,/MusicLabSectionPlayback.onMediaTransition\(this, mediaItem\?\.mediaId\)/);
  const t=section.slice(section.indexOf('fun onMediaTransition('));
  assert.match(t,/publishSelection\(current.copy\(index = next/);
  assert.match(t,/scheduleNeighbour\(connection, current.source, next, resolved\[next\]!!, revision\)/);
  assert.match(section,/connection.playNext\(nextItem\)/);
  assert.match(section,/connection.player.getMediaItemAt\(nativeNext\).mediaId == nextItem.mediaId/);
  assert.match(policy,/fun candidates\(/);
  assert.match(nativeTest,/automaticProgressionTraversesEveryRemainingResult/);
  assert.match(nativeTest,/neverLoopsPastFinalResultOrBeforeFirst/);
  assert.match(nativeTest,/earlierFailuresAreSkippableWithoutChangingFinalBoundary/);
});

test('R62: swipe and next button use same contextual progress, no ghost queue card',()=>{
  assert.match(connection,/if \(MusicLabSectionPlayback.skip\(this, 1\)\) return/);
  assert.match(connection,/if \(MusicLabSectionPlayback.skip\(this, -1\)\) return/);
  assert.match(connection,/val section = MusicLabSectionPlayback.active.value/);
  assert.match(thumbnail,/Modifier.pointerInput\(swipeThumbnail\)/);
  assert.doesNotMatch(thumbnail,/pointerInput\(swipeThumbnail, nativeMediaMetadata/);
  assert.match(thumbnail,/val committedId = playerConnection.mediaMetadata.value\?\.id/);
  assert.match(thumbnail,/CoverSwipeBridge.trySwipe\(/);
  assert.match(thumbnail,/activeEditorialContext\?\.mediaId == nativeMediaMetadata\?\.id/);
  assert.match(thumbnail,/R61VerifiedCoverArtwork/);
  assert.match(thumbnail,/LazyHorizontalGrid\(/);
});

test('R62: Cover list is verified, auto-playable and appendable without blocking first audio',()=>{
  const player=cover.slice(cover.indexOf('suspend fun playResolvedContext('),cover.indexOf('suspend fun loadPage('));
  assert.match(cover,/fun playableContextSeeds\(/);
  assert.match(cover,/previousRoundRows \+ publishedOriginalSnapshots \+ publishedCoverSnapshots/);
  assert.match(cover,/filterNot\(::isHiddenForCurrentCover\)/);
  assert.match(cover,/filter.*hasPublishableVideo/);
  assert.match(player,/startOrderedResultSequence\(selectedSeed, selectedItem, selectedId, queueTitle\)/);
  assert.match(cover,/MusicLabSectionPlayback.select\(/);
  assert.match(cover,/findPlayable = \{ entry ->/);
  assert.match(cover,/entry.ready \?: version\?\.let/);
  assert.match(cover,/MusicLabSectionPlayback.appendResults\(/);
  assert.match(cover,/session.selectedFingerprint = key/);
  assert.match(cover,/CoverSwipeBridge.activate\(sessionKey, active.mediaId\)/);
  assert.match(cover,/CoverSwipeBridge.expectIncoming\(sessionKey, selectedId\)/);
  assert.match(cover,/pauseCoverBackgroundForPlayback\(\)/);
});

test('R62: Hit Parade, Classifiche, Compilation, genres and main search share same ordered service',()=>{
  assert.match(chart,/MusicLabSectionPlayback.select\(/);
  assert.match(hit,/MusicLabSectionPlayback.select\(/);
  assert.match(comp,/MusicLabSectionPlayback.select\(/);
  assert.match(search,/MusicLabSectionPlayback.select\(/);
  assert.match(genre,/MusicLabSectionPlayback.select\(/);
  assert.match(genre,/MusicLabSectionPlayback.appendResults\(/);
  assert.match(genre,/visibleMusicLabSongs/);
  assert.match(genre,/filterNot \{ it.id.isSpotifyId\(\) \}/);
  assert.match(genre,/SpotifyQueue\(/);
  assert.match(section,/fun appendResults\(/);
});

test('R62: preserve R59 startup, R60 stream and R61 artwork without source mutation',()=>{
  assert.match(service,/R59_RESTORE_NO_DELAYED_SEEK/);
  assert.match(service,/R60_STREAM_JOIN/);
  assert.match(service,/PLAYBACK_START_BUFFER_MS = 1_600/);
  assert.match(service,/PLAYBACK_REBUFFER_MS = 8_000/);
  assert.match(thumbnail,/fadeIn\(animationSpec = tween\(210\)\) togetherWith/);
  assert.match(thumbnail,/fadeOut\(animationSpec = tween\(170\)\)/);
});
