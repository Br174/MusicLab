import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/screens/';
const search=readFileSync(root+'search/SearchScreen.kt','utf8');
const home=readFileSync(root+'search/MusicLabSearchExploreHome.kt','utf8');
const nav=readFileSync(root+'NavigationBuilder.kt','utf8');
const charts=readFileSync(root+'search/MusicLabChartScreen.kt','utf8');
const library=readFileSync(root+'library/LibraryScreen.kt','utf8');
test('Charts and genres restored on empty query, standard search remains',()=>{
 assert.match(search,/MusicLabSearchExploreHome\(/);
 assert.match(home,/ExploreTabChip\("Classifiche"/);
 assert.match(home,/ExploreTabChip\("Generi musicali"/);
 assert.match(home,/Classifiche per Paese/);
 assert.match(nav,/musiclab_chart\/\{chartTitle\}/);
 assert.match(charts,/MusicLabChartsRepository/);
});
test('Spotify First chips remain unchanged',()=>{
 assert.match(library,/"PLAYLISTS" to/);
 assert.match(library,/"SPOTIFY" to/);
 assert.match(library,/"YOUTUBE_MUSIC" to/);
});
