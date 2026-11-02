import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/';
const read=p=>fs.readFileSync(root+p,'utf8');
const sec=read('playback/MusicLabSectionPlayback.kt');
const srv=read('playback/MusicService.kt');
const cn=read('playback/PlayerConnection.kt');
const player=read('ui/player/Player.kt');
const store=read('playback/MusicLabListPositionStore.kt');
const hit=read('ui/screens/search/MusicLabHistoricalHitParade.kt');
const chart=read('ui/screens/search/MusicLabChartScreen.kt');
const comp=read('ui/component/CompilationScreen.kt');

test('R42 one immediate selected track, not delayed replacement 1-2s later',()=>{
 assert.match(sec,/connection\.playSectionNow\(item, source\)/);
 assert.doesNotMatch(sec,/connection\.playQueue\(ListQueue/);
 assert.match(cn,/fun playSectionNow\(item: MediaItem, sectionTitle: String\)/);
 const direct=srv.split('fun playSectionNow(item: MediaItem, sectionTitle: String)',2)[1]?.split('fun playNow(item: MediaItem)',1)[0];
 assert.ok(direct);
 assert.match(direct,/pendingQueuePreparation\?\.cancel\(\)/);
 assert.match(direct,/\+\+queueRequestSerial/);
 assert.match(direct,/player\.setMediaItem\(item, 0L\)/);
 assert.match(direct,/player\.playWhenReady = true/);
 assert.doesNotMatch(direct,/delay\(|getInitialStatus\(|pendingQueuePreparation = scope\.launch/);
 assert.doesNotMatch(direct,/player\.prepare\(\)[\s\S]*?player\.prepare\(\)/);
});
test('R42 player seek is independent of list and no reset occurs after user drag',()=>{
 assert.match(player,/LaunchedEffect\(isCasting, mediaMetadata\?\.id\)/);
 assert.doesNotMatch(player,/LaunchedEffect\(isPlaying, isCasting\)/);
 assert.equal((player.match(/playerConnection\.seekTo\(it\)/g)||[]).length,4);
 assert.equal((player.match(/lastManualSeekTime = System\.currentTimeMillis\(\)/g)||[]).length,8);
 assert.match(player,/System\.currentTimeMillis\(\) - lastManualSeekTime > 400L/);
 assert.equal((player.match(/valueRange = 0f\.\.duration\.coerceAtLeast\(0L\)\.toFloat\(\)/g)||[]).length,4);
 assert.match(cn,/fun seekTo\(position: Long\)/);
});
test('R42 contextual next/previous updates persistent last-selected row reactively',()=>{
 assert.match(sec,/private fun publishSelection\(snapshot: Snapshot\)/);
 assert.match(sec,/MusicLabListPositionStore\.select\(key, selected\)/);
 assert.match(sec,/publishSelection\(current\.copy\(index = target/);
 assert.match(sec,/publishSelection\(current\.copy\(index = next/);
 assert.match(store,/val selectedKeys: StateFlow<Map<String, String>>/);
 assert.match(store,/selectedKeysFlow\.value = selectedKeysFlow\.value \+ \(key to selectedKey\)/);
 for(const source of [hit,chart]){
  assert.match(source,/MusicLabSectionPlayback\.active\.collectAsStateWithLifecycle\(\)/);
  assert.match(source,/MusicLabListPositionStore\.selectedKeys\.collectAsStateWithLifecycle\(\)/);
  assert.match(source,/selectionKey = (positionKey|listKey)/);
  assert.doesNotMatch(source,/animateScrollToItem\(/);
 }
 assert.match(comp,/selectionKey = "compilation:detail:" \+ current\.id/);
 assert.match(comp,/selectedTrackIndex = state\.index/);
});
test('R42 leaves verified Discogs original q search and R41 persistent memory',()=>{
 assert.match(comp,/query = criteria\.query,/);
 assert.match(comp,/perPage = if \(criteria\.language == null\) 100 else 25/);
 assert.match(hit,/MusicLabSectionMemory\.number\(/);
 assert.match(comp,/MusicLabCompilationSnapshot\.save\(/);
 assert.match(read('ui/component/DiscogsDirectVersionBrowser.kt'),/CoverRoundStorage\.load\(context, sessionKey\)/);
});
