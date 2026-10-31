import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const src='app/src/main/kotlin/com/metrolist/music/';
const last=readFileSync('app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt','utf8');
const charts=readFileSync(src+'ui/screens/search/MusicLabChartScreen.kt','utf8');
const art=readFileSync(src+'ui/screens/search/MusicLabChartArtwork.kt','utf8');
const home=readFileSync(src+'ui/screens/search/MusicLabSearchExploreHome.kt','utf8');

test('R31 Last.fm geographic and world chart parse the documented tracks JSON object',()=>{
 assert.match(last,/val parent = root\["tracks"\]\?\.jsonObject/);
 assert.match(last,/parseRankedTopTracks\(responseText\)/);
 assert.doesNotMatch(last,/root\[if \(countryName == null\) "tracks" else "toptracks"\]/);
 assert.match(last,/track\["@attr"\]\?\.jsonObjectOrNull\(\)\?\.intValue\("rank"\)/);
 assert.match(last,/track\["image"\]/);
});
test('R31 display uses image-left song title and artist without triangle',()=>{
 assert.match(charts,/MusicLabChartArtwork\(\s*track/);
 assert.match(charts,/Text\(track\.title/);
 assert.match(charts,/Text\(track\.artist/);
 assert.doesNotMatch(charts,/else "▶"/);
 assert.match(art,/AsyncImage\(/);
 assert.match(art,/Text\("♫"/);
 assert.match(art,/size\(56\.dp\)/);
});
test('R31 artwork request is lazy, bounded and checks identity',()=>{
 assert.match(art,/produceState<String\?>\(/);
 assert.match(art,/Semaphore\(3\)/);
 assert.match(art,/withTimeoutOrNull\(3500L\)/);
 assert.match(art,/sameRecordingLabel\(track\.title, track\.artist/);
 assert.match(art,/ConcurrentHashMap/);
 assert.match(home,/navController.navigate\("musiclab_chart\/" \+ Uri.encode\(chart.title\)\)/);
});
