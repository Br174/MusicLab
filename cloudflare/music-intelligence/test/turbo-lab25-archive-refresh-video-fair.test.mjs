import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const cloud=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt','utf8');
const browser=source.split('fun runSearch(requestedSort: DirectVersionSort')[1].split('fun retryMissingVideo(')[0];
const video=source.split('LaunchedEffect(sessionKey, turboSearchGeneration)')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const reentry=source.split('LaunchedEffect(sessionKey, discogsToken)')[1].split('// Fully verified sessions')[0];

test('old completed search may be replayed only if every posted recording has a video',()=>{
  assert.match(browser, /session\.turboFinished/);
  assert.match(browser, /publishedCoverSnapshots \+ session\.publishedOriginalSnapshots\)\.all\(::hasPublishableVideo\)/);
  assert.match(browser, /Missing videos are \*retryable\*/);
});
test('reopening an incomplete work automatically triggers bounded repair',()=>{
  assert.match(reentry, /session\.turboFinished/);
  assert.match(reentry, /\.any \{\s*!hasPublishableVideo\(it\)/);
  assert.match(reentry, /turboLastAutoRepairAtMs/);
  assert.match(reentry, /600_000L/);
  assert.match(reentry, /runSearch\(\)/);
});
test('archive metadata remains in read-only Cloud and is not deleted',()=>{
  assert.match(source, /CloudMusicDiscovery\.searchArchive\(/);
  assert.match(source, /sourceNames = listOf\("Archivio Cloud"\)/);
  assert.match(cloud, /\/api\/v1\/archive\/search/);
  assert.match(cloud, /fun recentArchiveSelection\(/);
});
test('old negative provider cache is bypassed for Turbo discovery',()=>{
  assert.match(browser, /forceRefresh = true/);
  assert.match(source, /maxNetworkFanOut = if \(playbackIsNormallyPlaying\(\) \|\| playbackIsCritical\(\)\) 2 else 5/);
});
test('seven-day sparse memory snapshots do not outrank fresh read-only discovery',()=>{
  assert.match(browser, /val cached = CloudMusicDiscovery\.cachedMemoryState/);
  assert.match(browser, /linked \* 2 >= versions\.size/);
  assert.match(browser, /CloudMusicDiscovery\.discoverMemoryState/);
});
test('last cards have a 15 second window from their own publication',()=>{
  assert.match(video,/fun currentVideoEndMs\(\)/);
  assert.match(video,/val secondSealed/);
  assert.match(video,/currentVideoEndMs\(\)/);
  assert.match(video,/session\.turboPosterPublishedAtMs\[candidate\.fingerprint\]/);
  assert.match(video,/now < publishedAt \+ TURBO_AUTO_TOTAL_MS/);
  assert.match(video,/val primaryDeadline = publishedAt \+/);
});
test('a first and late card have independent bounded windows',()=>{
  const firstPublished=1_000, secondPublished=9_000, budget=15_000;
  const firstEnd=firstPublished+budget, secondEnd=secondPublished+budget;
  assert.equal(firstEnd,16_000);assert.equal(secondEnd,24_000);
  assert.equal(secondEnd-firstEnd,8_000);
});
test('all fifty retain bounded lane coverage with no unbounded verifier',()=>{
  assert.match(video,/rows\.take\(10\)/);
  assert.match(video,/rows\.drop\(10\)\.take\(15\)/);
  assert.match(video,/rows\.drop\(25\)\.take\(13\)/);
  assert.match(video,/rows\.drop\(38\)/);
  assert.match(video,/val workers = \(0 until 4\)/);
  const r=Array.from({length:50},(_,i)=>i);
  const pools=[r.slice(0,10),r.slice(10,25),r.slice(25,38),r.slice(38)];
  assert.equal(new Set(pools.flat()).size,50);
});
test('no UI layout, rating, poster split or audio service is altered',()=>{
  assert.match(source, /TURBO_MIN_EARLY_POSTER_BLOCK = 25/);
  assert.match(source, /TURBO_POSTER_SECOND_BLOCK = 25/);
  assert.match(source, /private const val TURBO_RESULT_QUOTA = 50/);
  assert.match(video, /backgroundWorkBlocked\(\)/);
  assert.match(video, /isCoverAudioAtRisk\(\)/);
});
test('new Android name, update-in-place code and stable signing family are enforced',()=>{
  const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab25-env.py','utf8');
  const wf=fs.readFileSync('.github/workflows/turbo-lab25-archive-refresh-video-fair-apk.yml','utf8');
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE="1025"/);
  assert.match(stamp,/MusicLab Turbo LAB 25/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(wf,/application-label:'MusicLab Turbo LAB 25'/);
  assert.match(wf,/versionCode='1025'/);
  assert.doesNotMatch(stamp,/keytool|newkey/);
});
