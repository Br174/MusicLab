import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const versions = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const uab = read('uab-project.env');

test('LAB40 uses an exact-recording hard gate before score ranking', () => {
  assert.match(resolver, /isHardCompatible/);
  assert.match(resolver, /if \(!artistFieldMatch && !titleNamesTargetArtist\) return false/);
  assert.match(resolver, /sameVersionIntent/);
  assert.match(resolver, /live candidate/i);
});

test('LAB40 never uses title-only YouTube fallback when performer is known', () => {
  assert.match(resolver, /Every query keeps the target performer/);
  assert.match(resolver, /\$artist \$title official audio/);
  assert.doesNotMatch(resolver, /listOf\([\s\S]{0,180}\n\s*title,\n\s*\)/);
});

test('LAB40 stores pre-resolved SongItems and starts selected item before hydrating the queue', () => {
  assert.match(browser, /preparedVideoSongs/);
  assert.match(browser, /first sound wins/);
  assert.match(browser, /items = listOf\(selectedSong\.toMediaItem\(\)\)/);
  assert.match(browser, /Build the rest only after playback has been handed to the player/);
  assert.match(browser, /connection\.addToQueue\(tailItems\)/);
});

test('LAB40 tap path does not launch the long resolver', () => {
  assert.match(browser, /tapping a row must never launch the long resolver/);
  const playBlock = browser.slice(browser.indexOf('fun play(seed:'), browser.indexOf('LaunchedEffect(', browser.indexOf('fun play(seed:')));
  assert.doesNotMatch(playBlock, /CompilationTrackResolver\.resolveTrack/);
});

test('LAB40 gives COVER.INFO first merge precedence and follows relationship pages', () => {
  const lanes = providers.slice(providers.indexOf('val lanes ='), providers.indexOf('val merged ='));
  assert.ok(lanes.indexOf('coverInfo.await()') < lanes.indexOf('musicBrainz.await()'));
  assert.match(providers, /song="\$cleanTitle" performer="\$cleanArtist"/);
  assert.match(providers, /directRelation = true/);
  assert.match(providers, /seed\.directRelation -> 10/);
  assert.match(providers, /fonte primaria/);
});

test('LAB40 keeps artwork fallback and Last.fm public fallback', () => {
  assert.match(versions, /i\.ytimg\.com\/vi\/\$videoId\/hqdefault\.jpg/);
  assert.match(providers, /discoverLastFmPublic/);
  assert.match(providers, /BuildConfig\.LASTFM_API_KEY/);
});

test('LAB40 APK identity is isolated from earlier LABs and MADRE', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB40"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB40"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab40"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB40"/);
});
