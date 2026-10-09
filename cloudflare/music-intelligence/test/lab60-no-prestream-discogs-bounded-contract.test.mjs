import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { collectSourceEvidence } from '../src/source-router.js';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const sources = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt', 'utf8');
const discogs = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt', 'utf8');
const worker = fs.readFileSync('cloudflare/music-intelligence/src/worker-v20-runtime.js', 'utf8');
const updateFamily = fs.readFileSync('uab-project.env', 'utf8');

test('LAB60 discovery does not request audio stream URL before user tap', () => {
  const start = browser.indexOf('suspend fun resolveVideoChunk(');
  const end = browser.indexOf('fun videoPriorityPool(');
  assert.ok(start > 0 && end > start);
  const scan = browser.slice(start, end);
  assert.doesNotMatch(scan, /getStreamUrl\(/);
  assert.match(scan, /CompilationTrackResolver\.isHardCompatible/);
  assert.match(scan, /The native player/);
  const tap = browser.slice(browser.indexOf('suspend fun playResolvedContext('), browser.indexOf('suspend fun loadPage('));
  assert.match(tap, /connection\.playQueue\(/);
  assert.doesNotMatch(tap, /getStreamUrl\(/);
});

test('LAB60 provider savings: Last.fm and Wikidata do not run from Android Cover discovery', () => {
  const scan = sources.slice(sources.indexOf('suspend fun discover('), sources.indexOf('private fun discoverMusicBrainz('));
  assert.doesNotMatch(scan, /sourceLane \{ discoverLastFm/);
  assert.doesNotMatch(scan, /sourceLane \{ discoverWikidata/);
  assert.match(scan, /CoverSourceDiagnostic\("Last\.fm", false, 0/);
  assert.match(scan, /CoverSourceDiagnostic\("Wikidata", false, 0/);
  assert.match(scan, /val sourceGate = Semaphore\(maxNetworkFanOut\.coerceIn\(1, 3\)\)/);
});

test('LAB60 Cloudflare Cover expansion keeps disabling slow/duplicated services', () => {
  assert.match(worker, /disableLastFm: input\?\.mode === 'cover'/);
  assert.match(worker, /disableWikidata: input\?\.mode === 'cover'/);
});

test('LAB60 Cloudflare source router performs no Last.fm or Wikidata network calls when disabled', async () => {
  const calls = [];
  const results = await collectSourceEvidence({
    title: 'Caruso',
    artist: 'Lucio Dalla',
    env: { LASTFM_API_KEY: 'enabled-for-test' },
    fetchImpl: async (url) => {
      calls.push(String(url));
      return { ok: false, status: 503 };
    },
    options: { disableLastFm: true, disableWikidata: true },
  });
  assert.ok(calls.some(url => url.includes('musicbrainz.org')));
  assert.ok(calls.every(url => !url.includes('last.fm') && !url.includes('wikidata.org')));
  assert.equal(results.find(r => r.source === 'lastfm')?.status, 'unavailable');
  assert.equal(results.find(r => r.source === 'wikidata')?.status, 'unavailable');
});

test('LAB60 Discogs is bounded but preserves catalog candidates, and paging cannot silently block', () => {
  assert.match(browser, /DIRECT_VERSION_SOURCE_FETCH_SIZE = 30/);
  assert.match(browser, /DIRECT_COVER_RANK_MAX_SOURCE_PAGES = 5/);
  assert.match(browser, /DIRECT_COVER_INITIAL_DETAIL_BUDGET = 12/);
  assert.match(browser, /take\(DIRECT_COVER_INITIAL_DETAIL_BUDGET\)/);
  assert.match(browser, /mergePage\(results, verifiedRankSeeds, replace = false\)/);
  const next = browser.slice(browser.indexOf('fun loadNextPage()'), browser.indexOf('fun retryMissingVideo('));
  const coverBranch = next.slice(0, next.indexOf('if (backgroundWorkBlocked()) return'));
  assert.doesNotMatch(coverBranch, /if \(backgroundWorkBlocked\(\) \|\| currentPage/);
  assert.match(browser, /pageResult\.items\.map \{ seed ->/);
  assert.match(discogs, /MAX_DISCOVERY_CACHE_ENTRIES = 24/);
  assert.match(sources, /MAX_CACHED_DISCOVERIES = 24/);
});

test('LAB60 COVER.INFO publishes thumbnail candidates before following slow relations', () => {
  const begin = sources.indexOf('private suspend fun discoverCoverInfo(');
  const done = sources.indexOf('private data class CoverInfoSeed(');
  const scan = sources.slice(begin, done);
  const early = scan.indexOf('if (quickVideos.isNotEmpty()) onEarlyVideoCandidates(quickVideos)');
  const relation = scan.indexOf('relationRoots.chunked(2)');
  assert.ok(early > 0 && relation > early);
  assert.match(scan, /toCoverInfoCandidate\(seed, title\)/);
});

test('LAB60 uses same update family signature and a higher version code', () => {
  assert.match(updateFamily, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(updateFamily, /UAB_UPDATE_FAMILY_VERSION_CODE="1002"/);
  assert.match(updateFamily, /UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-turbo-family-02-test-reuses-lab-test-key"/);
});
