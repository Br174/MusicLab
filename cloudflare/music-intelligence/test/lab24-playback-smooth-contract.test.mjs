import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const service = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt',
  'utf8',
);
const mini = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt',
  'utf8',
);
const player = fs.readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt',
  'utf8',
);

test('LAB25 keeps current playback above all background preloading', () => {
  assert.match(service, /SMART_PRELOAD_TRACKS\s*=\s*1/);
  assert.match(service, /SMART_PRELOAD_PREFIX_BYTES\s*=\s*384L\s*\*\s*1024L/);
  assert.match(service, /Player\.STATE_BUFFERING\s*->\s*\{[\s\S]*smartPreloadJob\?\.cancel\(\)[\s\S]*preCacheJob\?\.cancel\(\)/);
  assert.match(service, /Player\.STATE_READY\s*->\s*scheduleSmartPlaybackPreload\(\)/);
  assert.match(service, /player\.totalBufferedDuration\s*>=\s*SMART_PRELOAD_STABLE_BUFFER_MS/);
});

test('LAB25 only warms a small prefix of the single next track', () => {
  assert.match(service, /repeat\(SMART_PRELOAD_TRACKS\)/);
  assert.match(service, /setLength\(prefixLength\)/);
  assert.match(service, /minOf\(contentLength, SMART_PRELOAD_PREFIX_BYTES\)/);
  assert.match(service, /songUrlCache\.put\(/);
  assert.doesNotMatch(
    service.slice(
      service.indexOf('private suspend fun prewarmMediaItemPrefix'),
      service.indexOf('private fun triggerPreCache'),
    ),
    /setLength\(if \(contentLength > 0\) contentLength/,
  );
});

test('LAB25 does not re-prepare an already playing queue when inserting Play Next', () => {
  const start = service.indexOf('fun playNext(items: List<MediaItem>)');
  const end = service.indexOf('fun addToQueue(items: List<MediaItem>)', start);
  assert.ok(start >= 0 && end > start);
  const body = service.slice(start, end);
  const addIndex = body.indexOf('player.addMediaItems(insertIndex, items)');
  const shuffleIndex = body.indexOf('if (shuffleEnabled)', addIndex);
  assert.ok(addIndex >= 0 && shuffleIndex > addIndex);
  assert.doesNotMatch(body.slice(addIndex, shuffleIndex), /player\.prepare\(\)/);
});

test('LAB25 removes title-search taps from mini-player but preserves full-player title search', () => {
  assert.doesNotMatch(mini, /SearchRoutes\.titleResultRoute/);
  assert.doesNotMatch(mini, /LocalNavController\.current/);
  assert.match(player, /SearchRoutes\.titleResultRoute\(title\)/);
});


test('LAB25 Cover uses direct Play Now instead of Play Next plus seek', () => {
  const cover = fs.readFileSync(
    'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
    'utf8',
  );
  const connection = fs.readFileSync(
    'app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt',
    'utf8',
  );
  assert.match(service, /fun playNow\(item: MediaItem\)/);
  assert.match(connection, /fun playNow\(item: MediaItem\)/);
  assert.match(cover, /connection\.playNow\(song\.toMediaItem\(\)\)/);
  assert.doesNotMatch(cover, /connection\.playNext\(song\.toMediaItem\(\)\)[\s\S]{0,120}connection\.seekToNext\(\)/);
});
