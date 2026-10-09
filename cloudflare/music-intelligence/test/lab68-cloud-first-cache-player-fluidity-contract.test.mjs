import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';

const ui=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const cloud=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt','utf8');
const providers=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const metadata=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/CoverPlaybackMemory.kt','utf8');
const env=fs.readFileSync('uab-project.env','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');

test('LAB68 caches Cloud responses on disk, with bounded capacity, age, payload, and IO dispatcher',()=>{
 assert.match(cloud,/suspend fun cachedMemoryState\(/);
 assert.match(cloud,/withContext\(Dispatchers\.IO\)/);
 assert.match(cloud,/MEMORY_MAX_ENTRIES = 8/);
 assert.match(cloud,/MEMORY_MAX_JSON_CHARS = 900_000/);
 assert.match(cloud,/MEMORY_TTL_MS = 7L \* 24 \* 60 \* 60 \* 1000/);
 assert.match(cloud,/getSharedPreferences\(MEMORY_PREFS, Context\.MODE_PRIVATE\)/);
 assert.match(cloud,/editor\.putString\("data_\$key", raw\)\.putLong\("date_\$key", now\)\.apply\(\)/);
 assert.match(cloud,/cacheContext\?\.let \{ storeMemorySnapshot/);
 assert.match(cloud,/parseMemoryRoot\(root\)/);
 assert.match(cloud,/rejectedVersions/);
});

test('LAB68 Cloud snapshot precedes remote await and heavy external sources',()=>{
 const start=ui.indexOf('val cachedMemoryDeferred = async(Dispatchers.IO)');
 const remote=ui.indexOf('val memoryDeferred = async(kotlinx.coroutines.Dispatchers.IO)',start);
 const awaitCache=ui.indexOf('val cachedMemory = cachedMemoryDeferred.await()',remote);
 const lookup=ui.indexOf('val memoryState = cachedMemory ?: memoryDeferred.await()',awaitCache);
 const fullWait=ui.indexOf('val firstPageLoaded = firstPageDeferred.await()',lookup);
 assert.ok(start>0 && remote>start && awaitCache>remote && lookup>awaitCache && fullWait>lookup);
 assert.match(ui,/cacheContext = context/);
 assert.match(ui,/val memoryVersions =/);
 assert.match(ui,/memoryVersions\.map\(::memoryCandidateToSeed\)/);
});

test('LAB68 early publication requires known metadata-compatible IDs, never cover-only posters',()=>{
 const early=ui.indexOf('// LAB68: a Cloud snapshot with previously HARD-compatible video');
 const wait=ui.indexOf('val firstPageLoaded = firstPageDeferred.await()',early);
 assert.ok(early>0 && wait>early);
 const part=ui.slice(early,wait);
 assert.match(part,/results\.any\(::hasPublishableVideo\)/);
 assert.match(part,/certifyForFrozenRanking\(/);
 assert.match(part,/publishedReadyLimit|publishReadyBatches\(\)/);
 assert.match(part,/rankingFrozen = true/);
 assert.match(ui,/CoverPlaybackMemory\.metadataVerifiedVideo\(context, seed\.fingerprint\) == id/);
 assert.match(metadata,/fun metadataVerifiedVideo\(/);
 assert.match(metadata,/fun saveMetadataVerifiedVideo\(/);
 assert.match(metadata,/METADATA_VERIFIED_TTL_MS = 24L \* 60 \* 60 \* 1000/);
 assert.match(ui,/CompilationTrackResolver\.isHardCompatible/);
 assert.doesNotMatch(part,/getStreamUrl\(|playQueue\(/);
});

test('LAB68 late providers cannot erase an early committed five-song ranking',()=>{
 assert.match(ui,/if \(rankingFrozen\) \{\s*syncStableOrder\(\)\s*\} else \{/);
 const a=ui.indexOf('suspend fun publishReadyBatches()'),b=ui.indexOf('suspend fun resolveNextVideoBatch(',a);
 const publish=ui.slice(a,b);
 assert.match(publish,/remaining\.takeWhile\(::videoAttemptSettled\)/);
 assert.match(publish,/settledPrefix\.filter\(::hasPublishableVideo\)/);
 assert.match(publish,/publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
});

test('LAB68 caps discovery fan-out, gives playback priority and memoizes expensive Compose order',()=>{
 assert.match(providers,/maxNetworkFanOut: Int = 2/);
 assert.match(providers,/Semaphore\(maxNetworkFanOut\.coerceIn\(1, 3\)\)/);
 assert.equal((ui.match(/maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\)\) 1 else 2/g)||[]).length,2);
 assert.match(ui,/val orderedPool = remember\(results, session\.stableOrder, resolvedOriginalArtist, sortMode, mode\)/);
 assert.match(ui,/DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1/);
 assert.doesNotMatch(ui,/getStreamUrl\(videoId/);
});

test('LAB68 preserves UI geometry and the byte-identical original player and update identity',()=>{
 const card=ui.slice(ui.indexOf('private fun DiscogsVersionCard('));
 assert.match(card,/Modifier\.height\(184\.dp\)/);
 assert.match(card,/Modifier\s*\.size\(144\.dp\)/);
 assert.match(card,/Modifier\.size\(76\.dp\)/);
 assert.match(card,/ContentScale\.Crop/);
 const sha=createHash('sha1').update(Buffer.concat([Buffer.from('blob '+player.length+'\0'),player])).digest('hex');
 assert.equal(sha,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
 assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
 assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6801"/);
});
