import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read=p=>fs.readFileSync(p,'utf8');
const mem=read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionMemory.kt');
const pos=read('app/src/main/kotlin/com/metrolist/music/playback/MusicLabListPositionStore.kt');
const hp=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt');
const hc=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt');
const cr=read('app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt');
const cp=read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt');
const cb=read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const explore=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt');
const online=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt');
const results=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt');
const editorial=read('app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabEditorialSnapshot.kt');

test('local durable section memory is isolated from playback and credential stores',()=>{
 assert.match(mem,/getSharedPreferences\(PREFS, Context\.MODE_PRIVATE\)/);
 assert.match(mem,/musiclab_section_memory_r41/);
 assert.doesNotMatch(mem,/playQueue\(|playNow\(|seekTo|DiscogsTokenKey|apiKey/);
 assert.match(pos,/fun attach\(context: Context\)/);
 assert.match(pos,/MusicLabSectionMemory\.writeState\(app, "position:\$key"/);
 assert.match(pos,/fun flush\(key: String\)/);
});
test('Hit Parade restores exact date, cached list and independently selected card',()=>{
 assert.match(hp,/MusicLabSectionMemory\.number\([\s\S]*?"hitparade", "last_date_ms"/);
 assert.match(hp,/MusicLabSectionMemory\.putNumber\(context, "hitparade", "last_date_ms"/);
 assert.match(hp,/MusicLabEditorialSnapshot\.loadHistorical\(context, dateKey\)/);
 assert.match(hp,/MusicLabEditorialSnapshot\.saveHistorical\(context, dateKey, it\)/);
 assert.match(hp,/MusicLabListPositionStore\.select\(positionKey, song\.rank\.toString\(\)\)/);
 assert.match(hp,/LazyListState\(initialPosition\.index, initialPosition\.offset\)/);
});
test('Chart and genres retain trusted rank and artwork without playback on restoration',()=>{
 assert.match(hc,/MusicLabEditorialSnapshot\.loadChart\(context, title\)/);
 assert.match(hc,/MusicLabEditorialSnapshot\.saveChart\(context, title, it\)/);
 assert.match(editorial,/track\.sourceArtworkTrusted/);
 assert.match(hc,/MusicLabListPositionStore\.attach\(context\)/);
 assert.match(hc,/MusicLabListPositionStore\.flush\(listKey\)/);
});
test('Compilation saves original R39 search state, all rows and selected release without token',()=>{
 assert.match(cr,/MusicLabCompilationSnapshot\.load\(context\)/);
 assert.match(cr,/MusicLabCompilationSnapshot\.save\(context,/);
 assert.match(cr,/openReleaseId = summary\.id/);
 assert.match(cr,/MusicLabListPositionStore\.flush\("compilation:results"\)/);
 assert.match(cp,/put\("results", JSONArray\(\)/);
 assert.doesNotMatch(cp,/DiscogsTokenKey|discogsToken|playNow\(/);
 assert.match(cr,/query = criteria\.query,/);
 assert.match(cr,/perPage = if \(criteria\.language == null\) 100 else 25/);
 assert.doesNotMatch(cr,/verifyContainingTrack\(/);
});
test('Cover and originals restore stable session archive identity with all filters and selected card',()=>{
 assert.match(cb,/MusicLabSectionMemory\.text\(context, "version:\$sectionIdentity", "visit"\)/);
 assert.match(cb,/sessionKey = buildString/);
 assert.match(cb,/CoverRoundStorage\.load\(context, sessionKey\)/);
 assert.match(cb,/MusicLabSectionMemory\.writeState\(context, "version:\$sectionIdentity"/);
 assert.match(cb,/MusicLabListPositionStore\.select\("version:\$sectionIdentity", seed\.fingerprint\)/);
 assert.match(cb,/MusicLabListPositionStore\.flush\("version:\$sectionIdentity"\)/);
});
test('Explore, suggestions and main search persist independent tab, query and filter state',()=>{
 assert.match(explore,/MusicLabSectionMemory\.number\(context, "explore", "tab"/);
 assert.match(explore,/MusicLabListPositionStore\.flush\("explore:home"\)/);
 assert.match(online,/MusicLabListPositionStore\.attach\(context\)/);
 assert.match(results,/MusicLabSectionMemory\.text\(context, listKey, "search_filter"\)/);
 assert.match(results,/MusicLabListPositionStore\.select\(listKey, item\.id\)/);
 assert.match(results,/MusicLabListPositionStore\.flush\(listKey\)/);
});
