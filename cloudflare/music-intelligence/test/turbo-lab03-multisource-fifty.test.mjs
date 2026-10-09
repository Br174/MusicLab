import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const sources = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const env = read('uab-project.env');

test('Turbo 03 retains read-only separated package update and forty-second global ceiling',()=>{
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1003"/);
  assert.match(browser,/TURBO_BURST_DEADLINE_MS = 40_000L/);
  assert.match(browser,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
});
test('COVER.INFO dedicated priority lane, no Last.fm or Wikidata requests',()=>{
  assert.match(sources,/val coverInfo = async\(Dispatchers\.IO\)/);
  assert.match(sources,/val lane = reportResult\(discoverCoverInfo\(/);
  assert.match(sources,/val sourceGate = Semaphore\(maxNetworkFanOut\.coerceIn\(1, 3\)\)/);
  const active = sources.slice(sources.indexOf('suspend fun discover('), sources.indexOf('val resolvedMusicBrainz ='));
  assert.doesNotMatch(active,/discoverLastFm\(/);
  assert.doesNotMatch(active,/discoverWikidata\(/);
});
test('Every other supported provider reports independent completed results',()=>{
  assert.match(sources,/onSourceCompleted: suspend \(List<CoverSourceCandidate>, CoverSourceDiagnostic, Int\?\)/);
  assert.match(sources,/reportResult\(discoverMusicBrainz\(cleanTitle, mode, lookup\), lookup\.work\?\.originalYear\)/);
  for (const provider of ['discoverITunes','discoverSpotify','discoverLrcLib']) {
    assert.ok(sources.includes('reportResult(sourceLane { '+provider+'('));
  }
  assert.match(browser,/sourceEvidence\.addAll\(items\)/);
  assert.match(browser,/sourceStatus\[diagnostic\.name\] = diagnostic/);
  assert.match(browser,/sourceEvidence\.toList\(\) \+/);
  assert.match(browser,/external\?\.diagnostics\?\.forEach/);
});
test('COVER.INFO retains entire early search page including candidates without video IDs',()=>{
  assert.match(sources,/val partial = parseCoverInfoDocument\(parsed\)/);
  assert.match(sources,/if \(partial\.isNotEmpty\(\)\) onEarlyVideoCandidates\(partial\)/);
  assert.match(browser,/sourceStatus\["COVER.INFO"\] = CoverSourceDiagnostic/);
});
test('Documentary dates remain part of 20-to-1 ranking',()=>{
  assert.match(browser,/val originalYear = sourceOriginalYear\.get\(\)/);
  assert.match(browser,/DiscogsVersionSource\.certifyForFrozenRanking\(/);
  const certify = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
  assert.match(certify,/if \(knownYear != null\) score \+= 2/);
  assert.match(certify,/if \(knownYear == null\) score = score\.coerceAtMost\(11\)/);
});
test('All top fifty validated video cards are revealed in one atomic publication',()=>{
  assert.match(browser,/val finalRanked = coverCandidatePool\(\)/);
  assert.match(browser,/\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
  assert.match(browser,/publishedCoverSnapshots = finalRanked/);
  assert.match(browser,/visibleLimit = TURBO_RESULT_QUOTA/);
  assert.match(browser,/sourceDiagnostics = sourceDiagnostics/);
  assert.match(browser,/session\.turboFinished = true/);
  assert.match(browser,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
});
test('Category switches stay local; original player untouched',()=>{
  assert.match(browser,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) \{\s*\/\/ LAB03: local category changes/);
  assert.match(browser,/publishedCoverSnapshots\.filterNot\(::isHiddenForCurrentCover\)/);
  assert.match(env,/MUSICLAB_VERSION_CODE="1003"/);
});
