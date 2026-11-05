import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const path = 'app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt';
const src = readFileSync(path, 'utf8');
const native = readFileSync(
  'app/src/test/kotlin/com/metrolist/music/ui/component/CompilationTrackSubtitleTest.kt',
  'utf8'
);

test('Compilation hides FastPath only from subtitles, not from provider metadata', () => {
  assert.match(src, /internal fun compilationTrackSubtitle\(/);
  assert.match(src, /label\.equals\("MusicLab FastPath", ignoreCase = true\)/);
  assert.match(src, /compilationTrackSubtitle\(artist, resolved\?\.source, resolved != null\)/);
  assert.doesNotMatch(src, /Text\(\s*"\$artist · \$source"/);
  const internalSourceCount = (src.match(/CompilationResolvedTrack\(track, (?:it|song), "MusicLab FastPath", false\)/g) || []).length;
  assert.equal(internalSourceCount, 2, 'Keep both FastPath sources for player/cache');
});

test('Unresolved lines and other original source labels keep their behavior', () => {
  assert.match(src, /if \(!resolved\) return artist/);
  assert.match(src, /else "\$artist · \$label"/);
  assert.match(native, /internalFastPathLabelIsHiddenForResolvedSongs/);
  assert.match(native, /otherSourcesRemainVisible/);
  assert.match(native, /unresolvedRowsDisplayOnlyArtistButRemainPlayable/);
  assert.match(native, /emptyAndCaseInsensitiveFastPathLabelsDoNotLeaveDanglingSeparator/);
});

test('Original playback/prewarm paths remain untouched in this pure UI edit', () => {
  assert.match(src, /val fast = MusicLabFastPath\.peek\(/);
  assert.match(src, /MusicLabFastPath\.findArtwork\(/);
  assert.match(src, /MusicLabSectionPlayback\.select\(/);
  assert.match(src, /compilationTrackSubtitle\(artist, resolved\?\.source, resolved != null\)/);
});
