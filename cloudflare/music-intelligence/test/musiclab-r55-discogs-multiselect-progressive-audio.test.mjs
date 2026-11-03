import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read = p => fs.readFileSync(p,'utf8');
const base = 'app/src/main/kotlin/com/metrolist/music/';
const ui = read(base+'ui/component/CompilationScreen.kt');
const mode = read(base+'discogs/DiscogsSearchMode.kt');
const api = read(base+'discogs/DiscogsClient.kt');
test('R55: three checkboxes represent all seven nonempty combinations', () => {
  for (const combination of ['COMPILATION_SINGLE','ALBUM_SINGLE','ALL','BOTH']) assert.ok(mode.includes(combination));
  assert.match(mode,/fun toggle\(category: DiscogsReleaseCategory\)/);
  assert.match(mode,/next = if \(includes\(category\)\) selected - category/);
  assert.match(ui,/DiscogsReleaseCategory.entries.forEach/);
  assert.match(ui,/Checkbox\(/);
  assert.match(ui,/mode = mode.toggle\(category\)/);
  assert.doesNotMatch(ui,/DiscogsSearchMode.entries.map \{ it.label \}/);
});
test('R55: Singles searches Discogs track and format directly', () => {
  assert.match(api,/addQueryParameter\("format", "Single"\)/);
  assert.match(api,/addQueryParameter\("track", trackTitle.trim\(\)\)/);
  assert.match(mode,/SINGLE -> isSingle/);
  assert.match(ui,/includeSingles = false/);
  assert.match(ui,/criteria.mode.accepts\(formatLabels\)/);
});
test('R55: progressively verified exact tracklist, bounded IO, playback priority', () => {
  assert.match(ui,/val permits = Semaphore\(3\)/);
  assert.match(ui,/onVerified\(candidate\)/);
  assert.match(ui,/results = \(results \+ confirmed\).distinctBy/);
  assert.match(ui,/isPlaybackPriorityBurstActive\(\)/);
  assert.match(ui,/includeMasterVideos = false/);
  assert.match(ui,/release.tracks.none \{ track ->/);
  assert.match(ui,/CompilationLightSearchPolicy.accepts\(/);
  assert.match(ui,/selected = found.song.toMediaItem\(\)/);
});
