import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const service = read('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt');
const models = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt');
const client = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const coverFlow = read('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverFlowResolver.kt');
const coverScreen = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt');
const originalEngine = read('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionSearchEngine.kt');
const originalScreen = read('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt');
const identity = read('app/src/main/kotlin/com/metrolist/music/ui/component/GeminiOriginalDiscovery.kt');
const uab = read('uab-project.env');

test('LAB33 Player Veloce 3 lowers only startup reserve while preserving rebuffer protection', () => {
  assert.match(service, /PLAYBACK_START_BUFFER_MS = 900/);
  assert.match(service, /PLAYBACK_REBUFFER_MS = 4_000/);
  assert.match(service, /Player Veloce 2 fast stream lane/);
  assert.match(service, /!playbackPriorityBurstActive && dataStore\.get\(EnableQobuzKey, false\)/);
});

test('LAB33 Discogs client exposes advanced release filters and exact released date', () => {
  assert.match(client, /suspend fun searchReleases/);
  for (const field of ['track', 'artist', 'release_title', 'year', 'format', 'country', 'label', 'genre', 'style', 'catno']) {
    assert.ok(client.includes('addQueryParameter("' + field + '"'), 'missing Discogs filter ' + field);
  }
  assert.match(client, /sort_order/);
  assert.match(client, /normalizeDiscogsDate/);
  assert.match(models, /val releaseDate: String\?/);
  assert.match(models, /val formatDescriptions: List<String>/);
});

test('LAB33 uses one shared Discogs version source for Cover and Originali', () => {
  assert.match(source, /object DiscogsVersionSource/);
  assert.match(source, /discoverCoverVersions/);
  assert.match(source, /discoverOriginalVersions/);
  assert.match(source, /discoverTitleHints/);
  assert.match(coverFlow, /DiscogsVersionSource\.discoverCoverVersions/);
  assert.match(originalEngine, /DiscogsVersionSource\.discoverOriginalVersions/);
  assert.match(originalEngine, /DiscogsVersionSource\.discoverTitleHints/);
});

test('LAB33 semantic dedup allows same title but not identical versions', () => {
  assert.match(source, /fun dedupeVersions/);
  assert.match(source, /groupBy \{ it\.fingerprint \}/);
  assert.match(source, /DiscogsVersionKind\.LIVE/);
  assert.match(source, /DiscogsVersionKind\.REMIX/);
  assert.match(source, /versionFingerprint/);
  assert.match(coverScreen, /versionFingerprint/);
  assert.match(originalEngine, /versionFingerprint/);
});

test('LAB33 makes Discogs metadata first and other sources fallback/enrichment', () => {
  assert.match(coverFlow, /mergeCandidates\(discogsSeeds, primary\.versions, seeds\)/);
  assert.match(originalEngine, /val discogsResults = resolveDiscogsOriginalVersions/);
  const discogsIndex = originalEngine.indexOf('val discogsResults = resolveDiscogsOriginalVersions');
  const ytmIndex = originalEngine.indexOf('val music = if', discogsIndex);
  assert.ok(discogsIndex >= 0 && ytmIndex > discogsIndex);
});

test('LAB33 Cover and Originali display exact publication date when available', () => {
  assert.match(coverScreen, /Data pubblicazione:/);
  assert.match(coverScreen, /formatDiscogsPublicationDate/);
  assert.match(originalScreen, /Data pubblicazione:/);
  assert.match(originalScreen, /Prima pubblicazione:/);
  assert.match(originalScreen, /formatDiscogsPublicationDate/);
  assert.match(identity, /DISCOGS/);
});

test('LAB33 APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB33"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB33"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab33"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB33"/);
});
