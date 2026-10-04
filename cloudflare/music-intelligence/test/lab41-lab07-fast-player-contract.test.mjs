import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const versions = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const uab = read('uab-project.env');

test('LAB41 restores LAB07-style fast-first playback before the modern fallback', () => {
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  const fast = player.indexOf('extractionBundle.fastExtractor.extract');
  const full = player.indexOf('extractionBundle.extractor.extract', fast + 1);
  assert.ok(fast >= 0, 'fast extractor must exist');
  assert.ok(full > fast, 'modern full extractor must remain after fast lane');
  assert.match(player, /fastStream \?: requireNotNull/);
});

test('LAB41 fast lane never opens WebView or asks for PoToken', () => {
  const start = player.indexOf('private val fastTokenProvider');
  const end = player.indexOf('private val tokenProvider', start);
  const block = player.slice(start, end);
  assert.match(block, /providers = emptySet\(\)/);
  assert.match(block, /usesWebView = false/);
  assert.match(block, /PoTokenResult\? = null/);
});

test('LAB41 preserves the full modern PoToken resolver as fallback', () => {
  assert.match(player, /providers = setOf\(PoTokenProviderKind\.WEB_BOTGUARD\)/);
  assert.match(player, /usesWebView = true/);
  assert.match(player, /poTokenGenerator\.getWebClientPoToken/);
});

test('LAB41 remains isolated from LAB40 and MADRE', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB41"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB41"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab41"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB41"/);
});

test('LAB41 preserves LAB40 exact-recording and selected-first playback behavior', () => {
  assert.match(resolver, /isHardCompatible/);
  assert.match(resolver, /if \(!artistFieldMatch && !titleNamesTargetArtist\) return false/);
  assert.match(resolver, /sameVersionIntent/);
  assert.match(browser, /preparedVideoSongs/);
  assert.match(browser, /first sound wins/);
  assert.match(browser, /items = listOf\(selectedSong\.toMediaItem\(\)\)/);
  assert.match(browser, /connection\.addToQueue\(tailItems\)/);
});

test('LAB41 preserves LAB40 COVER.INFO, artwork and Last.fm behavior', () => {
  const lanes = providers.slice(providers.indexOf('val lanes ='), providers.indexOf('val merged ='));
  assert.ok(lanes.indexOf('coverInfo.await()') < lanes.indexOf('musicBrainz.await()'));
  assert.match(providers, /directRelation = true/);
  assert.match(providers, /seed\.directRelation -> 10/);
  assert.match(versions, /i\.ytimg\.com\/vi\/\$videoId\/hqdefault\.jpg/);
  assert.match(providers, /discoverLastFmPublic/);
});
