import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const source = path => readFileSync(path, 'utf8');
const service = source('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const connection = source('app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt');
const thumbnail = source('app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt');
const mini = source('app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt');
const policy = source('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt');

test('R35 stale queue completion cannot replace later user selection', () => {
  assert.match(service, /pendingQueuePreparation\?\.cancel\(\)/);
  assert.match(service, /pendingQueuePreparation = scope\.launch\(SilentHandler\)/);
  assert.ok((service.match(/isLatestQueueRequest\(requestSerial, queueRequestSerial\)/g) || []).length >= 5);
  assert.match(service, /fun playNow\(item: MediaItem\)[\s\S]*?pendingQueuePreparation\?\.cancel\(\)/);
  assert.match(policy, /fun isLatestQueueRequest\(/);
});
test('R35 player artwork follows Media3 committed queue rather than a scroll-only state', () => {
  assert.match(thumbnail, /currentMediaItemIndex\.collectAsState\(\)/);
  assert.match(thumbnail, /committedQueueIndex, shuffleModeEnabled, swipeThumbnail/);
  assert.match(thumbnail, /CoverSwipeBridge\.trySwipe\(/);
  assert.match(thumbnail, /playerConnection\.seekToNext\(\)/);
  assert.match(thumbnail, /playerConnection\.skipToPreviousItem\(\)/);
  assert.match(thumbnail, /thumbnailLazyGridState\.scrollToItem\(index\)/);
  assert.doesNotMatch(thumbnail, /snapshotFlow\s*\{/);
  assert.doesNotMatch(thumbnail, /thumbnailLazyGridState\.animateScrollToItem/);
});
test('R35 all player gestures navigate via the central audio controller', () => {
  assert.match(connection, /fun skipToPreviousItem\(\)/);
  assert.ok((mini.match(/playerConnection\.seekToNext\(\)/g)||[]).length >= 2);
  assert.ok((mini.match(/playerConnection\.skipToPreviousItem\(\)/g)||[]).length >= 2);
  assert.ok((mini.match(/signedSwipePixels\[0\]/g)||[]).length >= 6);
  assert.doesNotMatch(mini, /playerConnection\.player\.seekToNext\(\)/);
  assert.doesNotMatch(mini, /playerConnection\.player\.seekToPreviousMediaItem\(\)/);
});
