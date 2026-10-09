import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const meld=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const env=fs.readFileSync('uab-project.env','utf8');
function section(a,b){const i=browser.indexOf(a),j=browser.indexOf(b,i+a.length);assert.ok(i>=0&&j>i,'Missing '+a);return browser.slice(i,j);}

test('LAB66 keeps approved 144dp cover and 184dp card dimensions; no redesign',()=>{
  const card=browser.slice(browser.indexOf('private fun DiscogsVersionCard('));
  assert.match(card,/Modifier\.height\(184\.dp\)/);
  assert.match(card,/Modifier\s*\.size\(144\.dp\)/);
  assert.match(card,/Modifier\.size\(76\.dp\)/);
  assert.match(card,/contentScale = ContentScale\.Crop/);
});

test('LAB66 Coil requests are decoded to actual device pixels and cached across recomposition',()=>{
  const card=browser.slice(browser.indexOf('private fun DiscogsVersionCard('));
  assert.match(card,/with\(LocalDensity\.current\)/);
  assert.match(card,/\.roundToPx\(\)/);
  assert.match(card,/remember\(imageContext, stableArtworkUrl, imagePixels\)/);
  assert.match(card,/ImageRequest\.Builder\(imageContext\)/);
  assert.match(card,/\.size\(Size\(imagePixels, imagePixels\)\)/);
  assert.match(card,/\.memoryCachePolicy\(CachePolicy\.ENABLED\)/);
  assert.match(card,/\.diskCachePolicy\(CachePolicy\.ENABLED\)/);
  assert.match(card,/\.networkCachePolicy\(CachePolicy\.ENABLED\)/);
  assert.equal((card.match(/model = artworkRequest/g)||[]).length,2);
});

test('LAB66 never runs YouTube metadata resolution on main UI dispatcher',()=>{
  const chunk=section('suspend fun resolveVideoChunk(', 'fun videoPriorityPool(');
  assert.match(chunk,/withContext\(Dispatchers\.IO\) \{\s*YouTube\.queue/);
  assert.match(chunk,/withContext\(Dispatchers\.IO\) \{\s*CompilationTrackResolver\.resolveTrack/);
  assert.match(chunk,/DIRECT_VIDEO_EXISTING_ID_TIMEOUT_MS/);
  assert.match(chunk,/CompilationTrackResolver\.isHardCompatible/);
  assert.doesNotMatch(chunk,/getStreamUrl\(|playQueue\(/);
});

test('LAB66 fast first pass preserves full-depth retries and highest score budgets',()=>{
  assert.match(browser,/DIRECT_VIDEO_FIRST_PASS_TIMEOUT_MS = 2_400L/);
  assert.match(browser,/DIRECT_VIDEO_EXISTING_ID_TIMEOUT_MS = 1_400L/);
  assert.match(browser,/DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS = 3_800L/);
  assert.match(browser,/DIRECT_VIDEO_PARALLELISM = 2/);
  const prepare=section('suspend fun resolveNextVideoBatch(', '// LAB61: intentionally no media-metadata prewarming.');
  assert.match(prepare,/val firstPass = chunk\.all/);
  assert.match(prepare,/val budgetMs =/);
  assert.match(prepare,/if \(firstPass\) DIRECT_VIDEO_FIRST_PASS_TIMEOUT_MS/);
  assert.match(prepare,/else DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS/);
  const policy=section('fun autoVideoAttemptBudget(', 'fun needsAutomaticVideo(');
  assert.match(policy,/seed\.confidenceScore >= 17 -> 4/);
  assert.match(policy,/seed\.confidenceScore >= 10 -> 3/);
});

test('LAB66 avoids repeating full candidate sort after video-only updates',()=>{
  const update=section('fun replaceSeed(', 'fun playableTrack(');
  assert.match(update,/else if \(identityChanged \|\| !rankingFrozen\)/);
  assert.match(update,/syncStableOrder\(\)/);
  assert.doesNotMatch(update,/else \{\s*syncStableOrder\(\)/);
  assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
});

test('LAB66 yields verified partial batches promptly and never inserts artwork-only rows',()=>{
  assert.match(browser,/DIRECT_COVER_PARTIAL_BATCH_GRACE_MS = 2_800L/);
  const publish=section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(publish,/val settledPrefix = remaining\.takeWhile\(::videoAttemptSettled\)/);
  assert.match(publish,/val ready = settledPrefix\.filter\(::hasPublishableVideo\)/);
  assert.match(publish,/val group = ready\.take\(slots\)/);
  assert.match(publish,/publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
  assert.doesNotMatch(publish,/connection\.playQueue\(|getStreamUrl\(/);
});

test('LAB66 retains unchanged Android signing and upstream Meld player',()=>{
 const hash=createHash('sha1').update(Buffer.concat([Buffer.from('blob '+meld.length+'\0'),meld])).digest('hex');
 assert.equal(hash,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6601"/);
 assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
 assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});