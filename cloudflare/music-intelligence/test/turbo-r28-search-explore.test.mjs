import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/screens/search/';
const screen=readFileSync(root+'SearchScreen.kt','utf8');
const home=readFileSync(root+'MusicLabSearchExploreHome.kt','utf8');
const catalog=readFileSync(root+'MusicLabSearchExploreCatalog.kt','utf8');
test('R28 is empty query only, native search remains and online source remains unchanged',()=>{
 assert.match(screen,/if \(query\.text\.isBlank\(\)\) \{/);
 assert.match(screen,/MusicLabSearchExploreHome\(/);
 assert.match(screen,/else OnlineSearchScreen\(/);
 assert.match(screen,/SearchSource\.LOCAL -> \{/);
 assert.match(screen,/SearchSource\.ONLINE -> \{/);
 assert.match(screen,/val onSearch: \(String\) -> Unit/);
});
test('R28 two functional discovery tabs and complete country catalog',()=>{
 assert.match(home,/ExploreTabChip\("Classifiche"/);
 assert.match(home,/ExploreTabChip\("Generi musicali"/);
 assert.match(home,/var selectedTab by rememberSaveable \{ mutableStateOf\(0\) \}/);
 assert.match(catalog,/ExploreChart\("Top 50 Italia"/);
 assert.match(catalog,/ExploreChart\("Top 50 Globale"/);
 assert.match(catalog,/Country\("Vietnam"/);
 assert.match(catalog,/Country\("Stati Uniti"/);
 assert.match(home,/LazyColumn\(/);
});
test('R29 supersedes R28 external Spotify chart open while preserving real-source validation',()=>{
 assert.match(catalog,/playlist\.owner\?\.id\?\.equals\("spotify"/);
 assert.match(catalog,/playlist\.name\.trim\(\)\.equals\(chart\.spotifySearch/);
 assert.match(home,/navController.navigate\("musiclab_chart\/" \+ Uri.encode\(chart.title\)\)/);
 assert.doesNotMatch(home,/Intent\.ACTION_VIEW/);
 assert.doesNotMatch(home,/Spotify\.search\(chart\.spotifySearch/);
 assert.doesNotMatch(home,/LaunchedEffect\(/);
});
