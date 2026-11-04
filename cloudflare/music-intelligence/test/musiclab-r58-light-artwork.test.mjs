import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = 'app/src/main/kotlin/com/metrolist/music/';
const search = name => readFileSync(root + 'ui/screens/search/' + name, 'utf8');
const component = name => readFileSync(root + 'ui/component/' + name, 'utf8');
const light = search('MusicLabLightArtwork.kt');

test('existing native music engine retains ownership, extra sources are conditional and bounded', () => {
  assert.match(light, /private val gate = Semaphore\(2\)/);
  assert.match(light, /withTimeoutOrNull\(5_500L\)/);
  assert.match(light, /autoMissUntil\[k\]/);
  assert.match(light, /DiscogsClient\.searchReleases/);
  assert.match(light, /method=\$method/);
  assert.match(light, /LastFMApiKeyKey/);
  assert.match(light, /DiscogsTokenKey/);
  assert.match(light, /if \(!manual\) saved\(/);
  assert.doesNotMatch(light, /playNow\(|replaceMediaItem\(|prepare\(|seekTo\(/);
});

test('Top 50 and weekly and annual Hit Parade preserve tap and add long press', () => {
  for (const name of ['MusicLabChartScreen.kt','MusicLabHistoricalHitParade.kt','MusicLabHistoricalExtraCharts.kt']) {
    const source = search(name);
    assert.match(source, /combinedClickable\(onLongClick = \{ showArtworkSearch = true \}, onClick = \{/);
    assert.match(source, /MusicLabArtworkSearchDialog\(/);
    assert.match(source, /retrySignal = artworkRetry/);
  }
  assert.match(search('MusicLabHistoricalHitParade.kt'), /MusicLabSectionPlayback\.select/);
  assert.match(search('MusicLabChartScreen.kt'), /MusicLabSectionPlayback\.select/);
});

test('Cover keeps source SongItem video thumbnail, manual search on full row and existing video actions', () => {
  const source = component('CoverSearchScreen.kt');
  assert.match(source, /combinedClickable\(onClick = onPlay, onLongClick = \{ showArtworkSearch = true \}\)/);
  assert.match(source, /onMoreOptions = onLongClick/);
  assert.match(source, /MusicLabCustomCoverArtwork/);
  assert.match(search('MusicLabCustomCoverArtwork.kt'), /nativeThumbnail/);
  assert.match(search('MusicLabCustomCoverArtwork.kt'), /albumHint/);
  assert.match(search('MusicLabCustomCoverArtwork.kt'), /retrySignal > 0/);
});

test('Only curated genre rows gain manual action; original result rows stay as before', () => {
  const result = search('OnlineSearchResult.kt');
  assert.match(result, /if \(isMusicLabGenreQuery && item is SongItem\) showArtworkSearch = true/);
  assert.match(result, /else longClick\(\)/);
  assert.match(component('Items.kt'), /resolveGenreArtwork && item is SongItem/);
  assert.match(component('Items.kt'), /retrySignal = genreArtworkRetry/);
});

test('Video thumbnails are not persisted as discographic covers; last resort remains available', () => {
  const charts = search('MusicLabChartArtwork.kt');
  const discs = search('MusicLabHitParadeDiscArtwork.kt');
  assert.match(charts, /MusicLabLightArtwork\.isVideoPoster/);
  assert.match(discs, /val videoFallback by produceState/);
  assert.match(discs, /MusicLabFastPath\.find/);
  assert.match(discs, /MusicLabLightArtwork\.saved/);
  assert.match(light, /getSharedPreferences\(PREFS, Context\.MODE_PRIVATE\)/);
});
