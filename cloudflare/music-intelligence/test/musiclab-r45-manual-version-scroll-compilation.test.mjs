import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const player = read('app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt');
const song = read('app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt');
const store = read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabManualVersionStore.kt');
const section = read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt');
const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const hit = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt');
const comp = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const dialog = read('app/src/main/kotlin/com/metrolist/music/ui/component/YouTubeMatchDialog.kt');
test('manual link is accessible independent of Spotify and persistent per original', () => {
  assert.match(player, /if \(mediaMetadata.id.isNotBlank\(\)\)/);
  assert.match(song, /if \(song.id.isNotBlank\(\)\)/);
  assert.match(store, /MusicLabSectionMemory.writeState/);
  assert.match(store, /fun clear\(/);
  assert.match(service, /MusicLabManualVersionStore.apply\(this, original\)/);
  assert.match(section, /onManualVersionReplaced/);
  assert.match(dialog, /music.youtube.com/);
});
test('manual replacement does not append an unnecessary song to player queue', () => {
  assert.match(player, /replaceCurrentVersion\(/);
  assert.match(song, /replaceCurrentVersion\(/);
  const manual = player.slice(player.indexOf('if (showYouTubeMatchDialog)'), player.indexOf('val listenTogetherManager'));
  assert.doesNotMatch(manual, /playNext\(/);
});
test('selection reactivity does not animate restored viewport', () => {
  assert.doesNotMatch(hit, /animateScrollToItem\(/);
  assert.match(hit, /chart == null && restorePending/);
  assert.match(hit, /MusicLabListPositionStore.flush\(positionKey\)/);
});
test('R53 retires automatic Compilation fallback and preserves original broad q search', () => {
  const nav = comp.slice(comp.indexOf('LaunchedEffect(trackRequest?.requestId'), comp.indexOf('fun loadNextPage('));
  assert.match(nav, /query = target.title/);
  assert.doesNotMatch(nav, /runSearch\(/);
  assert.doesNotMatch(comp, /criteria.copy\(query = fallback\)/);
  assert.match(comp, /onClick = \{ runSearch\(\) \}/);
  assert.match(comp, /else if \(criteria.language == null\) 100 else 25/);
});
