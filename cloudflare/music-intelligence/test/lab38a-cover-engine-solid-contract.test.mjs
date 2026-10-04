import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const source = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const providers = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const discogs = read('app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt');
const uab = read('uab-project.env');

test('LAB38A keeps weak candidates instead of deleting them', () => {
  assert.match(source, /confidenceScore = 1/);
  assert.match(source, /isDisplayableDirectSeed/);
  assert.match(source, /seed\.confidenceScore in 1\.\.10/);
  assert.match(source, /Candidato Discogs: in attesa di verifica tracklist/);
  assert.doesNotMatch(source, /confidenceScore = 0,[\s\S]{0,120}Candidato release/);
});

test('LAB38A separates presentation sorting from network search', () => {
  assert.doesNotMatch(browser, /runSearch\(selected\)/);
  assert.match(browser, /rebuildStableOrder\(\)/);
  assert.match(browser, /visibleLimit = DIRECT_VERSION_PAGE_SIZE/);
  assert.match(browser, /val target = visibleLimit \+ DIRECT_VERSION_PAGE_SIZE/);
  assert.match(browser, /20 per blocco/);
});

test('LAB38A exposes the same shared UI rules to Cover and Originali including Straniere', () => {
  assert.match(browser, /DiscogsDirectMode\.COVER/);
  assert.match(browser, /DiscogsDirectMode\.ORIGINAL/);
  assert.match(browser, /DirectVersionCategory\.FOREIGN/);
  assert.match(browser, /Straniere · \$foreign/);
  assert.match(browser, /Cover · MusicLab/);
  assert.match(browser, /Originali · MusicLab/);
});

test('LAB38A source orchestrator uses only scout/evidence roles and integrates practical providers', () => {
  for (const name of ['MusicBrainz', 'Apple/iTunes', 'Last.fm', 'LRCLIB', 'Spotify', 'Wikidata', 'AI Scout']) {
    assert.ok(providers.includes(name), 'missing provider: ' + name);
  }
  assert.match(providers, /AI is never a judge/);
  assert.match(providers, /evidenceScore = 1/);
  assert.match(browser, /SourceDiagnosticsDialog/);
  assert.match(browser, /Nessuna fonte e nessuna AI elimina automaticamente un risultato/);
});

test('LAB38A protects Discogs with a process-wide request gate', () => {
  assert.match(discogs, /DISCOGS_MIN_REQUEST_INTERVAL_MS = 1_050L/);
  assert.match(discogs, /private val requestGate = Any\(\)/);
  assert.match(discogs, /awaitRequestSlot\(\)/);
  assert.match(discogs, /X-Discogs-Ratelimit-Remaining/);
  assert.match(discogs, /extendRequestGate\(retryAfterMs\)/);
});

test('LAB38A keeps background verification separate from row order and playback', () => {
  assert.match(browser, /scheduleDiscogsVerification/);
  assert.match(browser, /discogsVerificationChecked/);
  assert.match(browser, /playableTrack\(seed/);
  assert.match(browser, /scheduleVideoPreload/);
  assert.match(source, /mergeEvidence/);
  assert.match(source, /sourceNames/);
});

test('LAB38A APK identity is isolated', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB38A"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB38A"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab38a"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB38A"/);
});
