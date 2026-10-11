import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const read = (p) => fs.readFileSync(p,'utf8');
const providers=read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const browser=read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const certifier=read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const env=read('uab-project.env');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('LAB04 COVER.INFO title/artist and title-only requests start concurrently',()=>{
  const begin=providers.indexOf('private suspend fun discoverCoverInfo(');
  const end=providers.indexOf('private fun toCoverInfoCandidate(',begin);
  const part=providers.slice(begin,end);
  assert.ok(part.includes('searchTerms.map { term ->'));
  assert.ok(part.includes('async(Dispatchers.IO)'));
  assert.ok(part.includes('}.awaitAll()'));
  assert.ok(part.includes('per-page-songs'));
  assert.ok(part.includes('200'));
  assert.ok(part.includes('if (pageCandidates.isNotEmpty()) onEarlyVideoCandidates(pageCandidates)'));
  assert.ok(part.includes('val publicSearchReachable = searchResponses.any { it != null }'));
});
test('LAB04 relations are concurrent, bounded and preserve early evidence',()=>{
  const part=providers.slice(providers.indexOf('private suspend fun discoverCoverInfo('),providers.indexOf('private fun toCoverInfoCandidate('));
  assert.ok(part.includes('Semaphore(if (turbo) 6 else 2)'));
  assert.ok(part.includes('relationRoots.map { root ->'));
  assert.ok(part.includes('gate.withPermit'));
  assert.ok(part.includes('if (partial.isNotEmpty()) onEarlyVideoCandidates(partial)'));
  assert.ok(!part.includes('for (roots in relationRoots.chunked(2))'));
});
test('LAB04 all permitted auxiliary sources have a lane, no banned providers',()=>{
  const active=providers.slice(providers.indexOf('suspend fun discover('),providers.indexOf('val resolvedMusicBrainz ='));
  for(const f of ['val coverInfo = async','MusicBrainzCoverSource.lookup','discoverITunes(','discoverSpotify(','discoverLrcLib(']){
    assert.ok(active.includes(f),f);
  }
  assert.ok(!active.includes('discoverLastFm('));
  assert.ok(!active.includes('discoverWikidata('));
  assert.ok(active.includes('sourceGate = Semaphore(maxNetworkFanOut.coerceIn(1, if (BuildConfig.APPLICATION_ID'));
  assert.ok(browser.includes('maxNetworkFanOut = if (playbackIsNormallyPlaying()) 4 else 5'));
});
test('LAB04 verifies beyond the first hundred and reuses already verified IDs',()=>{
  const part=browser.slice(browser.indexOf('// LAB04: inspect the entire scored evidence pool'),browser.indexOf('// Prime Coil',browser.indexOf('// LAB04: inspect the entire scored evidence pool')));
  assert.ok(part.includes('val candidates = coverCandidatePool()'));
  assert.ok(part.includes('!hasPublishableVideo(seed) && playableTrack(seed) != null'));
  assert.ok(part.includes('val verifyFanOut = if (playbackIsNormallyPlaying()) 6 else 12'));
  assert.ok(part.includes('for (chunk in candidates.chunked(verifyFanOut))'));
  assert.ok(!part.includes('take(TURBO_MAX_RANK_CANDIDATES)'));
  assert.ok(part.includes('stopAt - TURBO_THUMBNAIL_RESERVE_MS'));
  assert.ok(browser.includes('CoverPlaybackMemory.metadataVerifiedVideo(context, seed.fingerprint) == id'));
});
test('LAB04 holds global 40s, ranks dates 20 to 1 and atomically publishes ready thumbnails',()=>{
  assert.ok(browser.includes('TURBO_BURST_DEADLINE_MS = 40_000L'));
  assert.ok(browser.includes('val stopAt = turboCommandStartedAt + TURBO_BURST_DEADLINE_MS'));
  assert.ok(browser.includes('TURBO_RESULT_QUOTA = 50'));
  assert.ok(browser.includes('context.imageLoader.execute('));
  assert.ok(browser.includes('publishedCoverSnapshots = finalRanked'));
  assert.ok(browser.includes('visibleLimit = TURBO_RESULT_QUOTA'));
  assert.ok(browser.includes('CloudMusicDiscovery.cancelArchiveRequests()'));
  assert.ok(browser.includes('sourceEvidence.addAll(items)'));
  assert.ok(certifier.includes('if (knownYear != null) score += 2'));
  assert.ok(certifier.includes('if (knownYear == null) score = score.coerceAtMost(11)'));
  assert.ok(certifier.includes('score.coerceIn(1, 20)'));
});
test('LAB04 installs over Turbo LAB03 but cannot replace the LAB68 package, audio identical',()=>{
  assert.ok(env.includes('it.verlezza.musiclab.turbo01'));
  assert.ok(!env.includes('it.verlezza.musiclab.labupdate01'));
  assert.ok(env.includes('UAB_UPDATE_FAMILY_VERSION_CODE="1004"'));
  assert.ok(env.includes('UAB_UPDATE_FAMILY_ID="turbo01"'));
  const bytes=Buffer.concat([Buffer.from('blob '+player.length),Buffer.from([0]),player]);
  assert.equal(createHash('sha1').update(bytes).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
});
