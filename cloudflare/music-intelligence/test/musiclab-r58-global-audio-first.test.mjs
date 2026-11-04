import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const base = 'app/src/main/kotlin/com/metrolist/music/';
const read=p=>fs.readFileSync(base+p,'utf8');
const hp=read('ui/screens/search/MusicLabHistoricalHitParade.kt');
const disc=read('ui/screens/search/MusicLabHitParadeDiscArtwork.kt');
const ui=read('ui/component/CompilationScreen.kt');
const service=read('playback/MusicService.kt');
const section=read('playback/MusicLabSectionPlayback.kt');
const cache=read('playback/StreamUrlCache.kt');
const resolver=read('ui/screens/search/MusicLabHitParadeVideoResolver.kt');
// R64 retains the exact R58 tap implementation but adds a long-press gesture.
// Extract the real audio tap handler, not the obsolete .clickable modifier text.
const tapStart = hp.indexOf('val myTap = ++tapSerial');
const tapEnd = hp.indexOf('}).padding(vertical = 6.dp)', tapStart);
assert.ok(tapStart >= 0 && tapEnd > tapStart, 'Hit Parade must retain a tap handler');
assert.match(hp, /combinedClickable\(onLongClick = \{ showArtworkSearch = true \}, onClick = \{/);
const tap = hp.slice(tapStart, tapEnd);

test('R58 chart selection protects audio BEFORE any network video lookup',()=>{
  assert.match(tap,/player\?\.beginPlaybackPriorityBurst\("hit-parade-tap"\)/);
  assert.match(tap,/val ready = MusicLabHitParadeDiscArtwork\.rememberedPlayableMediaItem/);
  assert.match(tap,/MusicLabFastPath\.peek\(song\.title, song\.artist\)/);
  assert.match(tap,/val selectedMedia = ready \?: MusicLabHitParadeVideoResolver\.find/);
  assert.ok(tap.indexOf('beginPlaybackPriorityBurst("hit-parade-tap")') <
            tap.indexOf('MusicLabHitParadeVideoResolver.find('));
  assert.match(tap,/selected = selectedMedia/);
  assert.match(tap,/if \(myTap != tapSerial\) return@launch/);
});

test('R58 remembered pinned/manual original video skips YouTube queue on repeat',()=>{
  const remembered = disc.slice(disc.indexOf('fun rememberedPlayableMediaItem('),
                                 disc.indexOf('fun playableMediaItem(',disc.indexOf('fun rememberedPlayableMediaItem(')));
  assert.match(remembered,/MusicLabHitParadeVideoMemory\.get\(context, title, artist\)/);
  assert.match(remembered,/id = choice\.videoId/);
  assert.match(remembered,/thumbnailUrl = artwork\(context, title, artist, album = false\) \?: choice\.thumbnail/);
  assert.match(remembered,/MediaMetadata\.Artist\(/);
  assert.match(remembered,/\.toMediaItem\(\)/);
  assert.doesNotMatch(remembered,/YouTube\.queue|YouTube\.search|playNow|setMediaItem|player\.prepare/);
  assert.match(resolver,/if \(pinned\.manual\) return null/);
});

test('R58 compilation pauses release checks for album open and exact track selection',()=>{
  const open=ui.slice(ui.indexOf('fun openCompilation('),ui.indexOf('fun scheduleFullAudioLookup('));
  const play=ui.slice(ui.indexOf('fun playTrack('),ui.indexOf('fun saveCompilation('));
  assert.match(open,/verificationJob\?\.cancel\(\)/);
  assert.match(play,/verificationJob\?\.cancel\(\)/);
  assert.ok(play.indexOf('verificationJob?.cancel()') <
            play.indexOf('beginPlaybackPriorityBurst("compilation-track-tap")'));
  assert.match(ui,/val unverified = results\.filter \{ verifiedTrackIds\[it\.id\] == null \}/);
  assert.match(ui,/verifyIndexedTrackCandidates\(unverified, criteria, discogsToken, generation\)/);
  assert.match(ui,/MusicLabFastPath\.peek\(track\.title, artist, compatible\)/);
  assert.match(ui,/MusicLabFastPath\.find\(track\.title, artist, compatible\)/);
  assert.match(ui,/selected = found\.song\.toMediaItem\(\)/);
});

test('R58 global native audio keeps signed source and cache fast-paths',()=>{
  const source=service.slice(service.indexOf('private fun createDataSourceFactory('),
                             service.indexOf('private fun createMediaSourceFactory('));
  assert.ok(source.indexOf('songUrlCache[mediaId]?.let') <
            source.indexOf('val storedFormat = runBlocking(Dispatchers.IO)'));
  assert.ok(source.indexOf('if (mediaId.startsWith("local:"))') <
            source.indexOf('val storedFormat = runBlocking(Dispatchers.IO)'));
  assert.match(source,/val qobuzEnabled =\s*!playbackPriorityBurstActive && cachedEnableQobuz/);
  assert.match(source,/downloadCache\.isCached/);
  assert.match(cache,/expiresAtMillis <= currentTimeMillis/);
  assert.match(service,/playSectionEffective\(MusicLabManualVersionStore\.apply\(this, item\)/);
  assert.match(service,/PLAYBACK_START_BUFFER_MS = 1_600/);
  assert.match(section,/private fun playAt\(/);
  assert.match(section,/connection\.playSectionNow\(item, source\)/);
});

test('R58 diagnostics report truthful Media3 playing readiness, not first audible sample',()=>{
  const method=service.slice(service.indexOf('override fun onIsPlayingChanged(isPlaying: Boolean)'),
                             service.indexOf('override fun onPlaybackStateChanged('));
  assert.match(method,/if \(isPlaying && lab58SelectionAtMs > 0L\)/);
  assert.match(method,/R58_IS_PLAYING/);
  assert.match(method,/selection_to_isPlaying_ms=/);
  assert.match(service,/lab31AudioEvent\("STREAM_RESOLVE"/);
  assert.match(service,/lab31AudioEvent\("BUFFERING"/);
});
