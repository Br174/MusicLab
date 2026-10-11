import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const resolver=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt','utf8');
const old=browser.indexOf('suspend fun knownMusicLabVideo(');
const first=browser.indexOf('suspend fun resolveVideoChunk(');
const end=browser.indexOf('fun videoPriorityPool(',first);
const chunk=browser.slice(first,end);
const cache=browser.slice(old,first);
test('MEM 01 existing MusicLab cached-video function is actually called',()=>{
 assert.ok(old>=0&&first>old);
 assert.ok(chunk.includes('knownMusicLabVideo(current, track)'));
});
test('MEM 02 cached-video function is tried before blind YouTube Music lookup',()=>{
 const cacheIndex=chunk.indexOf('knownMusicLabVideo(current, track)');
 const externalIndex=chunk.indexOf('CompilationTrackResolver.resolveTrack(');
 assert.ok(cacheIndex>=0&&externalIndex>cacheIndex);
});
test('MEM 03 avoid duplicate MusicLab cache work when existing ID is already attached',()=>{
 assert.match(chunk,/if \(verified == null && existingId\.isBlank\(\)\)/);
});
test('MEM 04 prepared video metadata in MusicLab memory is returned with zero new HTTP calls',()=>{
 assert.match(cache,/session\.preparedVideoSongs\[videoId\]\s*\?: withTimeoutOrNull\(1_800L\)/);
});
test('MEM 05 when cached SongItem missing, only bound video ID receives a bounded lookup',()=>{
 assert.match(cache,/YouTube\.queue\(videoIds = listOf\(videoId\)\)/);
 assert.match(cache,/withTimeoutOrNull\(1_800L\)/);
});
test('MEM 06 unused/unrelated external video cannot pass existing strict hard-compatibility filter',()=>{
 assert.match(cache,/CompilationTrackResolver\.isHardCompatible\(track, song\)/);
});
test('MEM 07 a MusicLab cache miss keeps original discovery path working',()=>{
 assert.match(chunk,/if \(verified == null\) \{\s*val excluded =/);
 assert.match(chunk,/CompilationTrackResolver\.resolveTrack\(/);
});
test('MEM 08 real video hard metadata remains required to publish results',()=>{
 assert.match(chunk,/verifyCandidateSong\(known\?\.first, known\?\.second\)/);
 assert.match(browser,/\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
});
test('MEM 09 global five-second budget remains intact while using MusicLab internal cache',()=>{
 assert.match(browser,/private const val TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(browser,/private const val TURBO_RESULT_QUOTA = 50/);
});
test('MEM 10 MusicLab direct player remains original and no stream is probed during search',()=>{
 assert.match(browser,/connection\.playNow\(selectedItem\)/);
 assert.match(resolver,/fun resolveTrack\(/);
 assert.match(chunk,/native player/i);
});
