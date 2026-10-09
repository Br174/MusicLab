import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const read = path => fs.readFileSync(path, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const storage = read('app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt');
const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const player = read('app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt');
const mini = read('app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt');
const thumbnail = read('app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt');
const nativeResolver = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const env = read('uab-project.env');
const area = (a,b) => browser.slice(browser.indexOf(a),browser.indexOf(b,browser.indexOf(a)));

test('LAB63 no video-link lookup can block a tap or manual retry over 3 seconds',() => {
  assert.match(browser,/DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS = 3_000L/);
  assert.match(area('fun retryMissingVideo(seed:', 'fun play(seed:'),/withTimeoutOrNull\(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS\)/);
  assert.doesNotMatch(area('fun retryMissingVideo(seed:', 'fun play(seed:'),/CloudMusicDiscovery\.discoverMemoryState|CoverDiscoverySources\.discover/);
  assert.match(area('fun play(seed:', 'LaunchedEffect('),/withTimeoutOrNull\(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS\)/);
  assert.doesNotMatch(area('fun play(seed:', 'LaunchedEffect('),/getStreamUrl\(/);
});

test('LAB63 ranked thumbnails have one immutable image source even after video discovery',() => {
  assert.match(browser,/fun stableArtworkFor\(seed: DiscogsVersionSeed\)/);
  assert.match(browser,/CoverPlaybackMemory\.stableArtwork\(context, seed\.fingerprint, candidate\)/);
  assert.match(browser,/stableArtworkUrl = remember\(seed\.fingerprint/);
  const card = browser.slice(browser.indexOf('private fun DiscogsVersionCard('));
  assert.match(card,/model = artworkRequest/);
  assert.doesNotMatch(card,/model = videoId\.takeIf/);
  assert.match(storage,/fun stableArtwork\(/);
});

test('LAB63 mini/expanded/swipeable player image stays pinned by playing video ID',() => {
  for (const [name,component] of [['Player',player],['Mini',mini],['Thumbnail',thumbnail]]) {
    assert.match(component,/CoverPlaybackMemory\.stablePlayerMetadata\(/,name);
  }
  assert.match(browser,/CoverPlaybackMemory\.pinVideoArtwork\(context, selectedId, selectedArtwork\)/);
  assert.match(browser,/selectedSong\?\.toMediaMetadata\(\)\?\.copy\(thumbnailUrl = selectedArtwork/);
  assert.match(storage,/fun pinnedVideoArtwork\(/);
});

test('LAB63 retries rotate phrases, exclude known failed video IDs and prioritize live',() => {
  assert.match(resolver,/searchRound: Int = 0/);
  assert.match(resolver,/preferLive: Boolean = false/);
  assert.match(resolver,/allQueries\.drop\(shift\) \+ allQueries\.take\(shift\)/);
  assert.match(resolver,/track\.artists\.joinToString\(" "\) \+ " live"/);
  assert.match(browser,/CoverPlaybackMemory\.nextSearchRound\(context, current\.fingerprint\)/);
  assert.match(browser,/CoverPlaybackMemory\.rejectedVideoIds\(context, current\.fingerprint\)/);
  assert.match(storage,/MAX_REJECTED = 24/);
  assert.match(storage,/fun rejectVideo\(/);
});

test('LAB63 persistent link is pinned only after Media3 says the selected ID is playing',() => {
  assert.match(browser,/connection\.playbackState\.value == Player\.STATE_READY/);
  assert.match(browser,/connection\.isEffectivelyPlaying\.value/);
  assert.match(browser,/CoverPlaybackMemory\.saveVerifiedVideo\(context, selectedFingerprint, selectedId/);
  assert.match(browser,/CoverPlaybackMemory\.verifiedVideo\(context, currentSeed\.fingerprint\)/);
  assert.match(storage,/getSharedPreferences\(PREFS, Context\.MODE_PRIVATE\)/);
  assert.doesNotMatch(storage,/fetch\(|download\(|getStreamUrl\(/);
});

test('LAB63 upstream Meld remains byte-identical and APK family version increases in place',() => {
  const hash=createHash('sha1').update(Buffer.concat([
    Buffer.from('blob '+Buffer.byteLength(nativeResolver)+'\0'),Buffer.from(nativeResolver)
  ])).digest('hex');
  assert.equal(hash,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6801"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});
