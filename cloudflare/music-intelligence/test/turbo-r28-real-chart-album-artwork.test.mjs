import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const dir='app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const chart=readFileSync(dir+'MusicLabChartScreen.kt','utf8');
const artwork=readFileSync(dir+'MusicLabChartArtwork.kt','utf8');
const prepare=readFileSync('tools/universal-apk-builder/prepare-turbo-r28-real-artwork-33108-env.py','utf8');

test('R28 Last.fm valid HTTPS generic star is not accepted as song cover',()=>{
  assert.match(chart,/sourceArtworkTrusted: Boolean = true/);
  assert.match(chart,/artwork = it.artwork, sourceArtworkTrusted = false/);
  assert.match(artwork,/track\.sourceArtworkTrusted/);
  assert.match(artwork,/initialValue = track\.artwork\.takeIf \{ track\.sourceArtworkTrusted \}/);
  assert.doesNotMatch(artwork,/initialValue = track\.artwork,/);
});
test('R28 each chart row independently retrieves verified artist/title album artwork',()=>{
  assert.match(chart,/items\(result\.tracks/);
  assert.match(chart,/MusicLabChartArtwork\(/);
  assert.match(artwork,/private fun matchingCatalogueTrack\(/);
  assert.match(artwork,/titleScore\(wantTitle, candidateTitle\) < 5/);
  assert.match(artwork,/Spotify\.search\(/);
  assert.match(artwork,/found\.firstOrNull/);
  assert.match(artwork,/\?\.album\?\.images/);
});
test('R28 no-login Apple Music catalogue fallback is bounded, validated and off main',()=>{
  assert.match(artwork,/https:\/\/itunes\.apple\.com\/search\?term=/);
  assert.match(artwork,/connection\.connectTimeout = 2200/);
  assert.match(artwork,/connection\.readTimeout = 2200/);
  assert.match(artwork,/matchingCatalogueTrack\(/);
  assert.match(artwork,/artworkUrl100/);
  assert.match(artwork,/600x600bb/);
  assert.match(artwork,/withContext\(Dispatchers\.IO\)/);
});
test('R28 per-song artwork is persistent, independent from playback, and retains fallback',()=>{
  assert.match(artwork,/rememberArtwork\(context, track, fromCatalogue\)/);
  assert.match(artwork,/putString\("art:" \+ id/);
  // R67: the same native song lookup remains, but optional thumbnail work
  // now yields to Media3 during a new tap or rebuffer.
  assert.match(artwork,/val song = MusicLabArtworkAudioGate\.optionalLookup \{/);
  assert.match(artwork,/resolveSong\(context, track\)/);
  assert.match(artwork,/retryAfter/);
  assert.match(artwork,/Semaphore\(3\)/);
  assert.doesNotMatch(artwork,/initialValue = nowPlayingArtwork/);
});
test('R28 version increments with same Android package and certificate',()=>{
  assert.match(prepare,/33107","33108"/);
  assert.match(prepare,/it\.verlezza\.musiclab\.turbo01/);
  const workflow=readFileSync('.github/workflows/musiclab-r28-real-chart-artwork-33108-apk.yml','utf8');
  assert.match(workflow,/versionCode='33108'/);
  assert.match(workflow,/9A2F67CFB3C199836813ADDBF7BDFB0FA65EDC765FFACE4B6DF9490BB0962749/);
});
