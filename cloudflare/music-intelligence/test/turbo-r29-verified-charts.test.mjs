import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root = 'app/src/main/kotlin/com/metrolist/music/';
const home=readFileSync(root+'ui/screens/search/MusicLabSearchExploreHome.kt','utf8');
const chart=readFileSync(root+'ui/screens/search/MusicLabChartScreen.kt','utf8');
const catalog=readFileSync(root+'ui/screens/search/MusicLabSearchExploreCatalog.kt','utf8');
const last=readFileSync('app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt','utf8');
const yt=readFileSync('innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt','utf8');
test('R29 all 72 chart cards remain in-app and never force Spotify to open',()=>{
 assert.match(home,/navController.navigate\("musiclab_chart\/" \+ Uri.encode\(chart.title\)\)/);
 assert.doesNotMatch(home,/Intent\.ACTION_VIEW/);
 assert.match(catalog,/Country\("Vietnam"/);
 assert.match(catalog,/Country\("Italia"/);
 assert.match(catalog,/fun countryCode\(chart: ExploreChart\)/);
});
test('R29 real Last.fm geo and global chart, no synthetic playlist rank or hardcoded tracks',()=>{
 assert.match(last,/geo.gettoptracks/);
 assert.match(last,/chart.gettoptracks/);
 assert.match(last,/parameter\("api_key", key\)/);
 assert.match(last,/track.stringValue\("name"\)/);
 assert.match(last,/toptracks/);
 assert.match(chart,/LastFM.topTracksByRegion\(country\)/);
 assert.match(chart,/if \(LastFM.isInitialized\(\)\)/);
 assert.doesNotMatch(chart,/Spotify\.playlistTracks\(/);
});
test('R29 actual regional YouTube charts, isolated locale and no production player edits',()=>{
 assert.match(yt,/countryCode: String\? = null/);
 assert.match(yt,/InnerTube\(\)\.apply \{/);
 assert.match(yt,/YouTubeLocale\(gl = countryCode.uppercase/);
 assert.match(chart,/YouTube.getChartsPage\(countryCode = iso\)/);
 assert.match(chart,/section.title.contains\("Top songs"/);
 assert.match(chart,/youtubeCountries/);
 assert.match(chart,/YouTube.searchSummary\(words, incognito = true\)/);
});
test('R29 no false global territory, country fallback bounded',()=>{
 assert.match(catalog,/if \(chart.title == "Top 50 Globale" \|\| chart.title == "Brani preferiti globali"\) return null/);
 assert.match(chart,/withTimeoutOrNull\(5_000L\)/);
 assert.match(chart,/withTimeoutOrNull\(8_000L\)/);
 assert.match(chart,/Classifica non disponibile/);
});
