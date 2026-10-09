import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const player = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const env = fs.readFileSync('uab-project.env','utf8');
const block = (a,b) => {
  const i=browser.indexOf(a),j=browser.indexOf(b,i+a.length);
  assert.ok(i>=0 && j>i,'Missing section '+a);
  return browser.slice(i,j);
};

test('LAB67 parallel metadata: credits and recording details are independent IO tasks, one commit lane',()=>{
  const ranking=block('// LAB67: run independent Discogs credits', 'session.originalYear =');
  assert.match(ranking,/val creditsDeferred = async\(Dispatchers\.IO\)/);
  assert.match(ranking,/val detailsDeferred = async\(Dispatchers\.IO\)/);
  assert.match(ranking,/creditsDeferred\.await\(\) to detailsDeferred\.await\(\)/);
  assert.match(ranking,/val rankingEnriched =/);
  assert.ok(ranking.indexOf('creditsDeferred.await() to detailsDeferred.await()') < ranking.indexOf('mergePage(results, verifiedRankSeeds'));
  assert.match(ranking,/DIRECT_COVER_INITIAL_DETAIL_BUDGET/);
});

test('LAB67 nonblocking ID bootstrap and cloud persistence preserve quality checks',()=>{
  const freeze=block('rankingFrozen = true', 'session.visibleLimit = visibleLimit');
  assert.match(freeze,/scheduleVideoPreload\(\)/);
  assert.doesNotMatch(freeze,/withTimeoutOrNull\(4_200L\)/);
  const io=block('suspend fun resolveVideoChunk(', 'fun videoPriorityPool(');
  assert.match(io,/scope\.launch\(Dispatchers\.IO\) \{\s*persistCloudPlaybackBinding\(updated\)/);
  assert.match(io,/CompilationTrackResolver\.isHardCompatible/);
  assert.doesNotMatch(io,/getStreamUrl\(|playQueue\(/);
  assert.match(browser,/DIRECT_VIDEO_FIRST_PASS_TIMEOUT_MS = 2_400L/);
  assert.match(browser,/DIRECT_VIDEO_PARALLELISM = 2/);
});

test('LAB67 cover cards use verified video thumbnails and memoized requests, no layout redesign',()=>{
  const image=block('fun stableArtworkFor(seed:', 'suspend fun playResolvedContext(');
  assert.match(image,/mode == DiscogsDirectMode\.COVER && videoId != null/);
  assert.match(image,/https:\/\/i\.ytimg\.com\/vi\/\$videoId\/hqdefault\.jpg/);
  assert.match(image,/CoverPlaybackMemory\.stableArtwork/);
  assert.equal((browser.match(/stableArtworkUrl = remember\(seed\.fingerprint, seed\.coverUrl, seed\.resolvedVideoId\)/g)||[]).length,3);
  const card=browser.slice(browser.indexOf('private fun DiscogsVersionCard('));
  assert.match(card,/Modifier\.height\(184\.dp\)/);
  assert.match(card,/Modifier\s*\.size\(144\.dp\)/);
  assert.match(card,/Modifier\.size\(76\.dp\)/);
  assert.match(card,/ImageRequest\.Builder\(imageContext\)/);
  assert.match(card,/\.memoryCachePolicy\(CachePolicy\.ENABLED\)/);
});

test('LAB67 stable ordinal gate: no unverified previews; no score overtaking',()=>{
  const publish=block('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(publish,/remaining\.takeWhile\(::videoAttemptSettled\)/);
  assert.match(publish,/settledPrefix\.filter\(::hasPublishableVideo\)/);
  assert.match(publish,/publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
  assert.match(browser,/CoverPlaybackMemory\.verifiedVideo/);
  assert.match(browser,/DIRECT_COVER_PAGE_SIZE = 10/);
  assert.match(browser,/DIRECT_VIDEO_BATCH_SIZE = 5/);
});

test('LAB67 keeps Meld player byte-identical and Android update identity stable',()=>{
  const blob=createHash('sha1').update(Buffer.concat([Buffer.from('blob '+player.length+'\0'),player])).digest('hex');
  assert.equal(blob,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6701"/);
});
