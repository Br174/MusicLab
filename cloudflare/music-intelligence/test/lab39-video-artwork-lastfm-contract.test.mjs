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
