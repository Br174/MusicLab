import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (path) => fs.readFileSync(path, 'utf8');

const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const thumbnail = read('app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt');
const libraryMix = read('app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryMixScreen.kt');
const account = read('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt');
const uab = read('uab-project.env');

test('LAB32 Player Veloce 2 prepares extraction runtime before first tap', () => {
  assert.match(service, /InnerTubeXPlayer\.initialize\(this\)/);
  assert.match(service, /InnerTubeXPlayer\.prepare\(\)/);
});

test('LAB32 priority burst checks ready cache before optional provider discovery', () => {
  assert.match(service, /Player Veloce 2 fast stream lane/);
  assert.match(service, /if \(playbackPriorityBurstActive && !shouldBypassCache\)/);
  assert.match(service, /downloadCache\.isCached/);
  assert.match(service, /playerCache\.isCached/);
  assert.match(service, /songUrlCache\[mediaId\]/);
});

test('LAB32 skips Qobuz discovery during explicit playback priority burst', () => {
  assert.match(service, /val qobuzEnabled =\s*!playbackPriorityBurstActive && dataStore\.get\(EnableQobuzKey, false\)/);
  assert.match(service, /Player Veloce 2: skipping Qobuz during playback-priority burst/);
  assert.match(service, /priorityBurst=\$playbackPriorityBurstActive/);
});

test('LAB32 fixes player carousel duplicate-key crash structurally', () => {
  assert.match(thumbnail, /items\(\s*count = mediaItems\.size/);
  assert.match(thumbnail, /key = \{ index ->/);
  assert.match(thumbnail, /val item = mediaItems\[index\]/);
  assert.match(thumbnail, /"player_thumbnail_\$\{index\}_\$\{id\}"/);
  assert.doesNotMatch(thumbnail, /key = \{ item ->\s*item\.mediaId/);
});

test('LAB32 namespaces mixed library keys by item type', () => {
  assert.match(libraryMix, /library_mix_song_/);
  assert.match(libraryMix, /library_mix_playlist_/);
  assert.match(libraryMix, /library_mix_album_/);
  assert.match(libraryMix, /library_mix_artist_/);
});

test('LAB32 exposes one shared Last.fm and Discogs settings entry only', () => {
  assert.match(account, /Text\("Last\.fm \+ Discogs"\)/);
  assert.match(account, /API Key, Shared Secret e Personal Access Token/);
  assert.doesNotMatch(account, /title = \{ Text\(stringResource\(R\.string\.lastfm_api_credentials\)\) \}[\s\S]{0,250}title = \{ Text\(stringResource\(R\.string\.discogs_token\)\) \}/);
  assert.match(account, /var tempLastFmApiKey/);
  assert.match(account, /var tempLastFmSecret/);
  assert.match(account, /var tempDiscogsToken/);
  assert.match(account, /onLastFmApiKeyChange\(apiKey\)/);
  assert.match(account, /onDiscogsTokenChange\(tempDiscogsToken\.trim\(\)\)/);
});

test('LAB32 preserves saved credential storage keys and editor semantics', () => {
  assert.match(account, /remember\(lastFmApiKey, showExternalApiEditor\)/);
  assert.match(account, /remember\(lastFmSecret, showExternalApiEditor\)/);
  assert.match(account, /remember\(discogsToken, showExternalApiEditor\)/);
});

test('LAB32 APK identity remains isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB32"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB32"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab32"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB32"/);
});
