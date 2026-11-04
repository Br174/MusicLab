import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const dir='app/src/main/kotlin/com/metrolist/music/';
const read=p=>fs.readFileSync(dir+p,'utf8');
const thumb=read('ui/player/Thumbnail.kt');
const section=read('playback/MusicLabSectionPlayback.kt');
const policy=read('playback/MusicLabSwipeInputPolicy.kt');
const cover=read('ui/component/DiscogsDirectVersionBrowser.kt');
const audio=read('playback/MusicService.kt');
const connection=read('playback/PlayerConnection.kt');
const native=fs.readFileSync('app/src/test/kotlin/com/metrolist/music/playback/MusicLabSwipeInputPolicyTest.kt','utf8');

test('R63: native Compose drag recognizer receives horizontal gesture; no consumed-release observer',()=>{
  assert.match(thumb,/detectHorizontalDragGestures\(/);
  assert.match(thumb,/onHorizontalDrag = \{ change, dragAmount ->/);
  assert.match(thumb,/horizontalDistance \+= dragAmount/);
  assert.match(thumb,/change\.consume\(\)/);
  assert.match(thumb,/onDragEnd = \{/);
  assert.match(thumb,/rememberUpdatedState\(isPlayerExpanded\)/);
  assert.match(thumb,/viewConfiguration\.touchSlop \* 1\.6f/);
  assert.doesNotMatch(thumb,/awaitPointerEvent\(PointerEventPass\.Final\)/);
  assert.match(thumb,/Modifier\.pointerInput\(swipeThumbnail\)/);
});

test('R63: only one gesture owner: LazyHorizontalGrid cannot steal the input',()=>{
  assert.match(thumb,/LazyHorizontalGrid\(/);
  assert.match(thumb,/userScrollEnabled = false/);
  assert.doesNotMatch(thumb,/userScrollEnabled = isScrollEnabled/);
  assert.match(thumb,/playerConnection\.seekToNext\(\)/);
  assert.match(thumb,/playerConnection\.skipToPreviousItem\(\)/);
  assert.match(thumb,/MusicLabSectionPlayback\.isCurrent\(committedId\)/);
  assert.match(thumb,/CoverSwipeBridge\.trySwipe\(/);
});

test('R63: second fast swipe targets the next requested index; opposite swipe cancels pending request',()=>{
  assert.match(section,/private var pendingManualTarget: Int\? = null/);
  assert.match(section,/private var pendingManualAnchor: String\? = null/);
  assert.match(section,/private var pendingManualRevision = -1L/);
  assert.match(section,/MusicLabSwipeInputPolicy\.nextTarget\(/);
  assert.match(section,/requestJob\?\.cancel\(\)/);
  assert.match(section,/if \(firstTarget == current\.index\) \{/);
  assert.match(section,/pendingManualTarget = null/);
  assert.match(policy,/fun nextTarget\(/);
  assert.match(policy,/pendingTarget\?\.takeIf/);
  assert.match(native,/rapidSwipeAccumulatesIntendedStepsWithoutLosingInput/);
  assert.match(native,/oppositeSwipeCancelsPendingMoveBackToCurrent/);
  assert.match(native,/noPhantomWrapAtEitherResultBoundary/);
});

test('R63: immediate Cover neighbors are known IDs, not a new network search or all 50 eager artworks',()=>{
  const seq=cover.slice(cover.indexOf('fun startOrderedResultSequence('),
    cover.indexOf('suspend fun playResolvedContext('));
  assert.match(seq,/val nearby = sequenceIndex >= 0 && kotlin\.math\.abs\(index - sequenceIndex\) <= 2/);
  assert.match(seq,/session\.preparedVideoSongs\[id\]/);
  assert.match(seq,/MediaMetadata\(/);
  assert.match(seq,/ready = readyItem/);
  assert.match(seq,/findPlayable = \{ entry ->/);
  assert.doesNotMatch(seq,/resolveVideoChunk\(/);
  assert.match(cover,/CoverSwipeBridge\.expectIncoming\(sessionKey, selectedId\)/);
  assert.match(cover,/CoverSwipeBridge\.activate\(sessionKey, selectedId\)/);
});

test('R63: no change to verified previous artwork or automatic last-result continuation',()=>{
  assert.match(thumb,/R61VerifiedCoverArtwork/);
  assert.match(thumb,/fadeIn\(animationSpec = tween\(210\)\) togetherWith/);
  assert.match(thumb,/fadeOut\(animationSpec = tween\(170\)\)/);
  assert.match(thumb,/if \(!CoverSwipeBridge\.isCoverArtwork\(/);
  assert.match(section,/fun onPlaybackEnded\(connection: PlayerConnection\)/);
  assert.match(section,/OrderedResultAutoplayPolicy\.shouldAdvanceAtEnd/);
  assert.match(section,/scheduleNeighbour\(connection, current\.source, next, resolved\[next\]!!, revision\)/);
  assert.match(connection,/MusicLabSectionPlayback\.onPlaybackEnded\(this\)/);
  assert.match(audio,/R59_RESTORE_NO_DELAYED_SEEK/);
  assert.match(audio,/R60_STREAM_JOIN/);
  assert.match(audio,/PLAYBACK_START_BUFFER_MS = 1_600/);
});
