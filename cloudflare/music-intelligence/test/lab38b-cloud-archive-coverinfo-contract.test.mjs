import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const worker = read('cloudflare/music-intelligence/src/worker-v20.js');
const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const archive = read('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabArchiveScreen.kt');
const nav = read('app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt');
const uab = read('uab-project.env');
const wrangler = read('cloudflare/music-intelligence/wrangler.lab38b.jsonc');

test('LAB38B memory hides explicit user rejects but returns their negative-memory keys', () => {
  assert.match(worker, /user_rejected/);
  assert.match(worker, /rejectedVersions/);
  assert.match(worker, /filter\(row => Number\(row\.user_rejected \|\| 0\) !== 1\)/);
  assert.match(cloud, /rejectedKeys/);
  assert.match(browser, /session\.rejectedKeys/);
  assert.match(browser, /filterNot\(::isRejected\)/);
});

test('LAB38B manual approve or reject can create missing work and version rows', () => {
  assert.match(worker, /manual-user/);
  assert.match(worker, /INSERT INTO works/);
  assert.match(worker, /INSERT INTO versions/);
  assert.match(worker, /status === 'APPROVED'/);
  assert.match(worker, /status === 'REJECTED'/);
  assert.match(worker, /INSERT INTO decision_history/);
  assert.match(browser, /AiBrainDecisionStatus\.APPROVED/);
  assert.match(browser, /AiBrainDecisionStatus\.REJECTED/);
  assert.match(browser, /Approvata e salvata nell'Archivio MusicLab/);
  assert.match(browser, /Disapprovata: memorizzata e nascosta/);
});

test('LAB38B is cloud-first before fresh provider discovery', () => {
  const memory = browser.indexOf('CloudMusicDiscovery.discoverMemoryState');
  const external = browser.indexOf('CoverDiscoverySources.discover', memory);
  assert.ok(memory >= 0);
  assert.ok(external > memory);
  assert.match(browser, /Archivio Cloud/);
});

test('LAB38B archive exposes only approved non-rejected rows and is navigable in app', () => {
  assert.match(worker, /\/api\/v1\/archive\/search/);
  assert.match(worker, /v\.user_rejected=0/);
  assert.match(worker, /v\.user_verified=1 OR v\.decision_status='APPROVED'/);
  assert.match(cloud, /searchArchive/);
  assert.match(archive, /Archivio MusicLab/);
  assert.match(archive, /Cerca titolo, artista, originale/);
  assert.match(nav, /MusicLabArchiveScreen/);
  assert.match(browser, /MusicLabArchiveNavigationBridge\.ROUTE/);
});

test('LAB38B details are clickable and include approve reject controls', () => {
  assert.match(browser, /onSearch: \(String\) -> Unit/);
  assert.match(browser, /Tocca titolo, interprete, pubblicazione o un autore/);
  assert.match(browser, /Text\(if \(saving\) "Salvo…" else "Approva"\)/);
  assert.match(browser, /Text\("Disapprova"\)/);
});

test('LAB38B COVER.INFO adapter uses confirmed public no-key search route', () => {
  assert.match(providers, /https:\/\/cover\.info\/en\/search/);
  assert.match(providers, /addQueryParameter\("find", title\)/);
  assert.match(providers, /Jsoup\.parse/);
  assert.match(providers, /COVER\.INFO/);
  assert.match(providers, /sola discovery\/evidenza/);
});

test('LAB38B converges one unambiguous learned work across cover entry artists', () => {
  assert.match(worker, /titlePrefix/);
  assert.match(worker, /LIMIT 2/);
  assert.match(worker, /matches\.length === 1/);
  assert.match(browser, /resolvedOriginalArtist/);
  assert.match(browser, /memoryState[\s\S]*?original[\s\S]*?resolvedOriginalArtist/);
});

test('LAB38B deploy and APK identities are isolated', () => {
  assert.match(wrangler, /"name": "musiclab-music-intelligence"/);
  assert.match(wrangler, /"main": "src\/worker-v20-runtime\.js"/);
  assert.match(wrangler, /"ENGINE_VERSION": "1\.4\.0-lab38b"/);
  assert.match(uab, /UAB_FILE_VERSION="LAB38B"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB38B"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab38b"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB38B"/);
});
