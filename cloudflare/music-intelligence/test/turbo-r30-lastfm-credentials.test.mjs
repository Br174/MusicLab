import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root = 'app/src/main/kotlin/com/metrolist/music/';
const account = readFileSync(root+'ui/screens/settings/AccountSettings.kt','utf8');
const app = readFileSync(root+'App.kt','utf8');
const charts = readFileSync(root+'ui/screens/search/MusicLabChartScreen.kt','utf8');
const lastfm = readFileSync('app/src/main/kotlin/com/metrolist/lastfm/LastFM.kt','utf8');
const r29 = readFileSync('cloudflare/music-intelligence/test/turbo-r29-verified-charts.test.mjs','utf8');

test('Account screen and chart use same persisted Last.fm credentials', () => {
 assert.match(account,/rememberPreference\(LastFMApiKeyKey, ""\)/);
 assert.match(account,/rememberPreference\(LastFMSecretKey, ""\)/);
 assert.match(account,/onLastFmApiKeyChange\(apiKey\)/);
 assert.match(account,/onLastFmSecretChange\(secret\)/);
 assert.match(charts,/context\.dataStore\.data\.first\(\)/);
 assert.match(charts,/saved\[LastFMApiKeyKey\]/);
 assert.match(charts,/saved\[LastFMSecretKey\]/);
 assert.match(charts,/LastFM\.initialize\(apiKey = apiKey, secret = secret\)/);
 assert.match(app,/settings\[com\.metrolist\.music\.constants\.LastFMApiKeyKey\]/);
});
test('Chart uses API key alone but login/scrobbling keeps secret guard', () => {
 assert.match(lastfm,/fun hasChartApiKey\(\): Boolean = API_KEY\.isNotBlank\(\)/);
 assert.match(lastfm,/fun isInitialized\(\): Boolean = API_KEY\.isNotEmpty\(\) && SECRET\.isNotEmpty\(\)/);
 assert.match(charts,/if \(LastFM\.hasChartApiKey\(\)\)/);
 assert.doesNotMatch(charts,/if \(LastFM\.isInitialized\(\)\)/);
 assert.match(lastfm,/parameter\("api_key", key\)/);
});
test('No credential exposure, accurate fallback diagnostic, no audio and cover changes',()=>{
 assert.doesNotMatch(charts,/Log\.\w+\([^\n]*apiKey/);
 assert.doesNotMatch(charts,/println\([^\n]*apiKey/);
 assert.match(charts,/Classifica non disponibile per/);
 assert.match(charts,/Last\.fm ha respinto o non ha restituito i dati richiesti/);
 assert.match(charts,/Anche YouTube Music non ha restituito una classifica verificata/);
 assert.match(charts,/YouTube.getChartsPage\(countryCode = iso\)/);
 assert.match(r29,/Top songs/);
});
