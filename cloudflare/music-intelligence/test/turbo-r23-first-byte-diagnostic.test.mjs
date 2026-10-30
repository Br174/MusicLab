import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const service=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const baseline=readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt','utf8');
const cover=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');

function segment(begin,end) {
 const i=service.indexOf(begin),j=service.indexOf(end,i+begin.length);
 assert.ok(i>=0&&j>i,'missing '+begin);
 return service.slice(i,j);
}

test('R23 follows the same native data source, no new remote probes',()=>{
 const factory=segment('private fun createObservedCacheDataSource()','private fun createDataSourceFactory(');
 assert.match(factory,/val underlyingFactory = createCacheDataSource\(\)/);
 assert.match(factory,/val delegate = underlyingFactory\.createDataSource\(\)/);
 assert.match(factory,/object : DataSource by delegate/);
 assert.match(factory,/delegate\.open\(dataSpec\)/);
 assert.match(factory,/delegate\.read\(buffer, offset, length\)/);
 assert.doesNotMatch(factory,/YouTube|playerResponseForPlayback|OkHttpClient|\.prepare\(\)/);
});
test('R23 compares stream data open and first bytes with same tapped media',()=>{
 const factory=segment('private fun createObservedCacheDataSource()','private fun createDataSourceFactory(');
 assert.match(factory,/STREAM_DATA_OPEN/);
 assert.match(factory,/STREAM_DATA_FIRST_READ/);
 assert.match(factory,/since_tap_ms/);
 assert.match(factory,/since_open_ms/);
 assert.match(factory,/dataSpec\.key\?\.let\(::stripQobuzCacheKeyPrefix\) == lab31TappedMediaId/);
 assert.match(service,/lab31TappedMediaId = item\.mediaId/);
 assert.match(service,/lab31FirstReadReportedTapMs = 0L/);
});
test('R23 preserves R22 buffer recovery, R21 audio diagnostics and Cover priorities',()=>{
 assert.match(baseline,/MAX_BUFFER_STALL_RECOVERIES = 2/);
 assert.match(service,/BUFFER_STALL_RECOVERY/);
 assert.match(service,/AUDIO_UNDERRUN/);
 assert.match(service,/PLAYBACK_START_BUFFER_MS = 900/);
 assert.match(service,/PLAYBACK_REBUFFER_MS = 6_000/);
 assert.match(cover,/CoverSwipeBridge.bind\(sessionKey\)/);
 const order=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt','utf8');
 assert.match(order,/it == "cover.info"/);
});
