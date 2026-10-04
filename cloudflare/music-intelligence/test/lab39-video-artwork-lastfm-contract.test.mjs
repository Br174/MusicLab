import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const resolver = read('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt');
const versions = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const workflow = read('.github/workflows/lab39-video-artwork-lastfm-gate.yml');
const uab = read('uab-project.env');

test('LAB39 rejects title-only video hits by an explicit different performer', () => {
  assert.match(resolver, /explicitPerformerConflict/);
  assert.match(resolver, /score -= 90/);
  assert.match(resolver, /titleNamesTargetArtist/);
});

test('LAB39 deepens video retrieval only after the normal lanes fail', () => {
  assert.match(resolver, /searchQueries\(track\)/);
  assert.match(resolver, /for \(fallbackQuery in queries\.drop\(1\)\)/);
  assert.match(resolver, /ricerca profonda/);
});

test('LAB39 fills missing card artwork from the verified YouTube video', () => {
  assert.match(versions, /coverUrl = seed\.coverUrl \?: "https:\/\/i\.ytimg\.com\/vi\/\$videoId\/hqdefault\.jpg"/);
});

test('LAB39 Last.fm diagnostics distinguish missing build key from API rejection', () => {
  assert.match(providers, /BuildConfig\.LASTFM_API_KEY\.trim\(\)/);
  assert.match(providers, /chiave non presente nella build/);
  assert.match(providers, /root\.optInt\("error", 0\)/);
  assert.match(providers, /API non valida\/non autorizzata/);
});

test('LAB39 retries LRCLIB once and reports reachable empty results separately', () => {
  assert.match(providers, /fetchArrayWithRetry/);
  assert.match(providers, /HTTP\/rete non disponibile dopo 2 tentativi/);
  assert.match(providers, /raggiungibile · nessuna corrispondenza/);
});

test('LAB39 build fails if Last.fm secrets are absent and verifies generated BuildConfig', () => {
  assert.match(workflow, /LASTFM_API_KEY:[^\n]*secrets\.LASTFM_API_KEY/);
  assert.match(workflow, /LASTFM_SECRET:[^\n]*secrets\.LASTFM_SECRET/);
  assert.match(workflow, /vars\.LASTFM_API_KEY/);
  assert.match(workflow, /vars\.LASTFM_SECRET/);
  assert.match(workflow, /-z "\$LASTFM_API_KEY"/);
  assert.match(workflow, /-z "\$LASTFM_SECRET"/);
  assert.match(workflow, /LASTFM_API_KEY=%s/);
  assert.match(workflow, /Verify Last\.fm BuildConfig injection/);
});

test('LAB39 APK identity is isolated from LAB38B and MADRE', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB39"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB39"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab39"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB39"/);
});


const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const worker = read('cloudflare/music-intelligence/src/worker-v20.js');
const wrangler = read('cloudflare/music-intelligence/wrangler.lab39.jsonc');
const migration39 = read('cloudflare/music-intelligence/migrations/0004_lab39_cover_artwork.sql');

test('LAB39 cloud candidates resolve playback independently from Discogs first-page success', () => {
  assert.match(browser, /Cloud-first playback must not wait for Discogs pagination/);
  assert.match(browser, /Provider\/cloud video resolution is independent from Discogs page success/);
  assert.match(browser, /persistCloudPlaybackBinding/);
});

test('LAB39 persists and reuses cloud playback bindings without changing editorial decision', () => {
  assert.match(cloud, /savePlaybackBinding/);
  assert.match(cloud, /\/api\/v1\/playback\/binding/);
  assert.match(worker, /export async function savePlaybackBinding/);
  assert.match(worker, /INSERT INTO playback_bindings/);
  assert.match(worker, /playbackVideoId/);
  assert.match(worker, /playback_video_id/);
});

test('LAB39 persists artwork for learned cloud candidates', () => {
  assert.match(migration39, /ALTER TABLE versions ADD COLUMN cover_url TEXT/);
  assert.match(worker, /cover_url/);
  assert.match(cloud, /coverUrl/);
  assert.match(browser, /coverUrl = candidate\.coverUrl/);
});

test('LAB39 publishes isolated cloud runtime version', () => {
  assert.match(wrangler, /"ENGINE_VERSION": "1\.5\.0-lab39"/);
  assert.match(wrangler, /"BRAIN_VERSION": "39\.0"/);
});
