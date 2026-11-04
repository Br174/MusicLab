import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/';
const thumb=fs.readFileSync(root+'ui/player/Thumbnail.kt','utf8');
const bridge=fs.readFileSync(root+'ui/component/CoverSwipeBridge.kt','utf8');
const browser=fs.readFileSync(root+'ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const player=fs.readFileSync(root+'ui/player/Player.kt','utf8');
const audio=fs.readFileSync(root+'playback/MusicService.kt','utf8');
const tests=fs.readFileSync('app/src/test/kotlin/com/metrolist/music/ui/component/CoverSwipeBridgeTest.kt','utf8');

test('R61: real Cover carousel owns only outgoing and incoming verified IDs',()=>{
  assert.match(bridge,/fun isCoverArtwork\(actualVideoId: String\)/);
  assert.match(bridge,/actualVideoId == currentCoverVideoId/);
  assert.match(bridge,/actualVideoId == incomingCoverVideoId/);
  assert.match(bridge,/fun beginSelection\(/);
  assert.match(bridge,/fun expectIncoming\(/);
  assert.match(bridge,/fun activate\(/);
  assert.match(bridge,/fun clear\(/);
  assert.match(tests,/twoCorrectCoverFramesAndNoPlaylistGhost/);
  assert.match(tests,/secondSwipeCannotRaceAnUnfinishedSelection/);
  assert.match(tests,/otherOwnerAndEndOfSessionCannotBorrowCoverArt/);
});

test('R61: browser retains outgoing Cover until incoming ID is authorized BEFORE player mutation',()=>{
  const play=browser.slice(browser.indexOf('fun play(seed: DiscogsVersionSeed)'),
    browser.indexOf('fun swipeCover(',browser.indexOf('fun play(seed: DiscogsVersionSeed)')));
  assert.match(play,/CoverSwipeBridge\.beginSelection\(sessionKey\)/);
  assert.doesNotMatch(play,/CoverSwipeBridge\.stop\(sessionKey\)/);
  assert.match(play,/CoverSwipeBridge\.cancelSelection\(sessionKey\)/);
  const context=browser.slice(browser.indexOf('suspend fun playResolvedContext('),
    browser.indexOf('fun pauseCoverBackgroundForPlayback()')>0?
      browser.indexOf('suspend fun playResolvedContext(')+7300:undefined);
  assert.match(context,/CoverSwipeBridge\.expectIncoming\(sessionKey, selectedId\)/);
  assert.ok(context.indexOf('expectIncoming(sessionKey, selectedId)')<
    context.indexOf('connection.playNow(selectedItem)'));
  assert.match(context,/CoverSwipeBridge\.activate\(sessionKey, selectedId\)/);
});

test('R61: Cover poster has two-frame crossfade, no queue neighbor or snap resetting in Cover mode',()=>{
  assert.match(thumb,/val coverArtworkMode = CoverSwipeBridge\.isCoverArtwork/);
  assert.match(thumb,/if \(coverArtworkMode\) \{/);
  const cover=thumb.slice(thumb.indexOf('if (coverArtworkMode) {\n                        \/\/ R61: dedicated'),
    thumb.indexOf('} else {\n',thumb.indexOf('if (coverArtworkMode) {\n                        \/\/ R61: dedicated')));
  assert.match(cover,/AnimatedContent\(/);
  assert.match(cover,/contentKey = \{ it\?\.item\?\.mediaId/);
  assert.match(cover,/fadeIn\(animationSpec = tween\(210\)\) togetherWith/);
  assert.match(cover,/fadeOut\(animationSpec = tween\(170\)\)/);
  assert.doesNotMatch(cover,/LazyHorizontalGrid|player\.seekTo|scrollToItem/);
  assert.match(thumb,/if \(coverArtworkMode\) return@LaunchedEffect/);
  assert.match(thumb,/if \(!CoverSwipeBridge\.isCoverArtwork\(/);
  assert.match(thumb,/targetState = coverFrame \?: lastCoverFrame/);
});

test('R61: ordinary queue carousel, gestures and player audio are unchanged',()=>{
  assert.match(thumb,/LazyHorizontalGrid\(/);
  assert.match(thumb,/getMediaItems\(playerConnection.player, swipeThumbnail\)/);
  assert.match(thumb,/CoverSwipeBridge\.trySwipe/);
  assert.match(thumb,/playerConnection\.seekToNext\(\)/);
  assert.match(thumb,/playerConnection\.skipToPreviousItem\(\)/);
  assert.match(player,/Thumbnail\(/);
  assert.match(audio,/R60_STREAM_JOIN/);
  assert.match(audio,/R59_RESTORE_NO_DELAYED_SEEK/);
  assert.match(audio,/PLAYBACK_START_BUFFER_MS = 1_600/);
});
