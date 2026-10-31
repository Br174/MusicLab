import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const dir='app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const charts=readFileSync(dir+'MusicLabChartScreen.kt','utf8');
const art=readFileSync(dir+'MusicLabChartArtwork.kt','utf8');
const explore=readFileSync(dir+'MusicLabSearchExploreHome.kt','utf8');
const online=readFileSync(dir+'OnlineSearchScreen.kt','utf8');

test('R26 lists show title then artwork and no source/provider caption',()=>{
  assert.match(charts,/TopAppBar\(/);
  assert.match(charts,/MusicLabChartArtwork\(/);
  assert.doesNotMatch(charts,/item\(key = "source"\)/);
  assert.match(charts,/items\(result.tracks/);
});
test('R26 player artwork is reused for the matching recording only',()=>{
  assert.match(charts,/CoverPlaybackMemory.stablePlayerMetadata/);
  assert.match(charts,/matchesPlayer/);
  assert.match(art,/norm\(track.title\) == norm\(title\)/);
  assert.match(art,/wantedArtist/);
  assert.match(art,/nowPlayingArtwork/);
});
test('R26 network failure is bounded, retryable, and maintains stable images',()=>{
  assert.match(art,/Semaphore\(3\)/);
  assert.match(art,/retryAfter/);
  assert.doesNotMatch(art,/unavailable.add/);
  assert.match(art,/withTimeoutOrNull\(3500L\)/);
  assert.match(art,/withTimeoutOrNull\(5000L\)/);
  assert.match(art,/sameRecordingLabel/);
  assert.match(art,/onError/);
});
test('R26 charts genres and genre search results clear the mini-player bottom inset',()=>{
  assert.match(charts,/miniPlayerBottomInset \+ 16.dp/);
  assert.match(explore,/miniPlayerBottomInset \+ 16.dp/);
  assert.match(online,/LocalPlayerAwareWindowInsets.current.only\(WindowInsetsSides.Bottom\)/);
  assert.doesNotMatch(explore,/bottom = 112.dp/);
});
