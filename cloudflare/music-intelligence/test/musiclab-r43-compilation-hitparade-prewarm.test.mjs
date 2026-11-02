import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');
const comp = read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const fast = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt');
const policy = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt');
const hit = read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt');

test('R43 auto-prewarms each composed Compilation row just like Hit Parade', () => {
  assert.match(comp, /LaunchedEffect\(detail\.id, index\)/);
  assert.match(comp, /onVisibleTrack\(index\)/);
  assert.match(comp, /suspend fun warmVisibleTrack\(index: Int, current: DiscogsCompilationDetail\)/);
  assert.match(comp, /MusicLabFastPath\.find\(/);
  assert.match(comp, /CompilationTrackResolver\.isHardCompatible/);
  assert.match(comp, /CompilationSessionStore\.resolvedTracks\[index\] = found/);
  assert.match(hit, /MusicLabFastHitArtwork\(MusicLabChartTrack\(/);
  assert.match(fast, /flights\.computeIfAbsent/);
});

test('R43 protects playback priority, on-tap fallback and new compilation boundaries', () => {
  const visible = comp.split('suspend fun warmVisibleTrack(', 2)[1].split('    // Keep the original first-two head start', 1)[0];
  assert.match(visible, /visibleWarmGate\.withPermit/);
  assert.match(visible, /isPlaybackPriorityBurstActive\(\)/);
  assert.match(visible, /detail\?\.id != current\.id/);
  assert.doesNotMatch(visible, /playQueue|playNow|MusicLabSectionPlayback\.select|CompilationTrackResolver\.resolveTrack/);
  assert.match(comp, /MusicLabSectionPlayback\.select\(/);
  assert.match(comp, /CompilationTrackResolver\.resolveTrack\(track, current\.videos\)/);
  assert.match(comp, /CompilationSessionStore\.resolvedTracks\.clear\(\)/);
  assert.match(policy, /COMPILATION_VISIBLE_WARM_PARALLELISM = 2/);
  assert.match(policy, /SEARCH_PARALLELISM = 4/);
});

test('R43 removes manual play-all without changing full-audio and save actions', () => {
  assert.doesNotMatch(comp, /Riproduci tutto per brani|onPlayAll|resolveAllAndPlay|progressiveAlbumJob/);
  assert.match(comp, /Riproduci compilation completa/);
  assert.match(comp, /Salva compilation/);
  assert.match(comp, /onVisibleTrack: suspend \(Int\) -> Unit/);
  assert.match(comp, /onTrackClick = \{ index -> playTrack\(index, detail!!\) \}/);
  assert.match(comp, /fun saveCompilation\(current: DiscogsCompilationDetail\)/);
});
