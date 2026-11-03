import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const p = 'app/src/main/kotlin/com/metrolist/music/';
const read = path => fs.readFileSync(p + path, 'utf8');
const artwork = read('ui/screens/search/MusicLabHitParadeDiscArtwork.kt');
const weekly = read('ui/screens/search/MusicLabHistoricalHitParade.kt');
const extra = read('ui/screens/search/MusicLabHistoricalExtraCharts.kt');
const manual = read('ui/screens/search/MusicLabHitParadeVideoResolver.kt');
const coverPlayer = read('playback/CoverPlaybackMemory.kt');
const playerContext = read('playback/MusicLabSectionPlayback.kt');

test('R50: one verified disc resolver is shared by all four Hit Parade lists', () => {
  assert.match(weekly, /MusicLabHitParadeDiscCover\(/);
  assert.match(extra, /MusicLabHitParadeDiscCover\(/);
  assert.match(extra, /album = mode.isAlbum/);
  assert.match(artwork, /Spotify.search\(/);
  assert.match(artwork, /itunes\.apple\.com\/search/);
  assert.match(artwork, /entity=album/);
  assert.match(artwork, /entity=song/);
  assert.match(artwork, /MusicLabHitParadeDiscArtwork.saved/);
});

test('R50: album results no longer show broken empty-square artworks', () => {
  assert.doesNotMatch(extra, /Text\("▣"\)/);
  assert.doesNotMatch(extra, /if \(row.artwork != null\)/);
  assert.match(artwork, /eligibleAlbum/);
  assert.match(artwork, /correctedArtist/);
  assert.match(extra, /Text\(displayArtist/);
});

test('R50: YouTube thumbnail is fallback, not persisted as true sleeve', () => {
  assert.match(artwork, /private fun rememberDisc/);
  assert.match(artwork, /private suspend fun songDisc/);
  assert.match(artwork, /private suspend fun appleAlbum/);
  assert.match(artwork, /private suspend fun spotifyAlbum/);
  assert.match(artwork, /fun fallback\(/);
  assert.match(artwork, /MusicLabHitParadeVideoMemory.get/);
  assert.doesNotMatch(artwork.slice(artwork.indexOf('private fun rememberDisc'),
    artwork.indexOf('fun reject(')), /FastPath|thumbnail/);
});

test('R50: live cover changes are projected to both players without restarting audio', () => {
  assert.match(artwork, /private fun synchronizeCurrentPlayer/);
  assert.match(artwork, /connection.mediaMetadata.value = current.copy\(thumbnailUrl = image\)/);
  assert.match(coverPlayer, /MusicLabHitParadeDiscArtwork.currentPlayerArtwork/);
  assert.match(coverPlayer, /val pinned = pinnedVideoArtwork/);
  assert.match(weekly, /MusicLabHitParadeDiscArtwork.registerPlaybackRows/);
  assert.match(extra, /MusicLabHitParadeDiscArtwork.registerPlaybackRows/);
  assert.match(weekly, /MusicLabHitParadeDiscArtwork.playableMediaItem/);
  assert.match(extra, /MusicLabHitParadeDiscArtwork.playableMediaItem/);
  assert.doesNotMatch(artwork + coverPlayer, /\.replaceMediaItem\(|\.seekTo\(|\.prepare\(/);
  assert.match(playerContext, /MusicLabSectionPlayback/);
});

test('R50: replacing or clearing manual video refreshes list artwork identity', () => {
  assert.match(manual, /MusicLabHitParadeDiscArtwork.onVideoChanged\(\)/);
  assert.match(artwork, /choice\?\.manual != true/);
  assert.match(artwork, /sameRecord\(title, artist, choice.title, choice.artist\)/);
  assert.match(artwork, /RecordIdentity\(choice.title.ifBlank/);
  assert.match(artwork, /snapshot.source != "Top annuali singoli"/);
});

test('R50: preserve original weekly parser, annual categories, menu and queue', () => {
  assert.match(weekly, /internal object MusicLabHistoricalHitParade/);
  assert.match(weekly, /MusicLabHistoricalHitParade.load\(selected\)/);
  assert.match(extra, /MusicLabHistoricalChartModeMenu\(mode, onModeChange\)/);
  assert.match(weekly, /MusicLabHitParadeVideoMenu/);
  assert.match(extra, /MusicLabHitParadeVideoMenu/);
  assert.match(extra, /SearchRoutes.resultRoute/);
  assert.match(weekly, /MusicLabSectionPlayback.select/);
  assert.match(extra, /MusicLabSectionPlayback.select/);
});
