import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const auto=source.split('LaunchedEffect(sessionKey, turboSearchGeneration) {')[1].split('fun pauseCoverBackgroundForPlayback()')[0];
const first=source.split('fun sealPostersOnce() {')[1].split('fun sealSecondPostersOnce() {')[0];
const second=source.split('fun sealSecondPostersOnce() {')[1].split('fun stopAtFullQuota()')[0];
const refill=auto.split('// LAB27: video-first refill')[1].split('registerClock()')[0];

test('first ten publish together when candidates ready, without 1.2s wait',()=>{
  assert.match(source,/TURBO_MIN_EARLY_POSTER_BLOCK = 10/);
  assert.match(first,/ranked\.size < TURBO_MIN_EARLY_POSTER_BLOCK/);
  assert.doesNotMatch(first,/turboCommandStartedAt\s*</);
  assert.match(first,/publishedCoverSnapshots = firstBlock\.filterNot/);
});
test('next forty publish atomically as soon as all available',()=>{
  assert.match(source,/TURBO_POSTER_SECOND_BLOCK = 40/);
  assert.match(second,/if \(next\.size < TURBO_POSTER_SECOND_BLOCK\) return/);
  assert.match(second,/publishedCoverSnapshots = publishedCoverSnapshots \+ next/);
  assert.doesNotMatch(second,/turboCommandStartedAt\s*</);
});
test('the original performer leads and highest confidence sorts remain',()=>{
  assert.match(source,/sortGroup\(originalVariants, DirectVersionSort\.RELEVANCE\)/);
  assert.match(source,/sortGroup\(coverCandidatePool\(\), DirectVersionSort\.RELEVANCE\)/);
});
test('LazyColumn keys preserve fixed slots through replacement',()=>{
  assert.match(source,/val turboSlotKeys: MutableMap<String, String>/);
  assert.match(source,/key = \{ seed -> if \(isTurbo\) "discogs_direct_cover_\$\{session\.turboSlotKeys/);
  assert.match(source,/key = \{ seed -> if \(isTurbo\) "discogs_direct_original_\$\{session\.turboSlotKeys/);
  assert.match(refill,/session\.turboSlotKeys\[fresh\.fingerprint\]/);
});
test('refill is video-first; thumbnail is derived from verified ID',()=>{
  assert.match(refill,/CoverDiscoverySources\.discover\(/);
  assert.match(refill,/lab21VerifyAlternative\(candidate\)/);
  assert.match(refill,/!hasPublishableVideo\(verified\)/);
  assert.match(refill,/val newPoster = "https:\/\/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg"/);
  assert.match(refill,/coverUrl = newPoster/);
  assert.doesNotMatch(refill,/retryMissingVideo\(|play\(/);
});
test('fresh source discovery is shared by every missing slot',()=>{
  assert.ok(refill.indexOf('CoverDiscoverySources.discover(') < refill.indexOf('for (missing in missingRows)'));
  assert.match(source,/TURBO_VIDEO_REFILL_DISCOVERY_MS = 7_000L/);
});
test('source identity and video ID duplicates are independently blocked',()=>{
  assert.match(refill,/distinctBy\(DiscogsVersionSource::recordingIdentityKey\)/);
  assert.match(refill,/DiscogsVersionSource\.recordingIdentityKey\(candidate\) !in owned/);
  assert.match(refill,/id in noDuplicateVideo/);
  assert.match(refill,/noDuplicateVideo\.add\(id\)/);
});
test('scores guide candidate preference, but there is no hard equality rule',()=>{
  assert.match(refill,/kotlin\.math\.abs\(it\.confidenceScore - missing\.confidenceScore\)/);
  assert.doesNotMatch(refill,/candidate\.confidenceScore == missing\.confidenceScore/);
});
test('replacement wave commits both snapshots once after search, never one by one',()=>{
  const scan=refill.split('// SINGLE COMMIT:')[0];
  const commit=refill.split('// SINGLE COMMIT:')[1];
  assert.doesNotMatch(scan,/publishedCoverSnapshots = publishedCoverSnapshots\.map/);
  assert.match(commit,/publishedOriginalSnapshots = publishedOriginalSnapshots\.map\(::swap\)/);
  assert.match(commit,/publishedCoverSnapshots = publishedCoverSnapshots\.map\(::swap\)/);
  assert.match(commit,/session\.stableOrder = session\.stableOrder\.map/);
});
test('bounded refill yields to buffering and preserves partial winners',()=>{
  assert.match(source,/TURBO_VIDEO_REFILL_MAX_MS = 45_000L/);
  assert.match(refill,/while \(backgroundWorkBlocked\(\) \|\|/);
  assert.match(refill,/isCoverAudioAtRisk\(\)/);
  assert.match(refill,/if \(swaps\.isNotEmpty\(\) && isActive\)/);
  assert.doesNotMatch(refill,/withTimeoutOrNull\(TURBO_VIDEO_REFILL_MAX_MS\)/);
});
test('simulation: 50 stable slots with soft scores and no duplicate video IDs',()=>{
  const old=Array.from({length:50},(_,i)=>({slot:i,score:20-Math.floor(i/3),video:i<27?'V'+i:null}));
  const candidates=Array.from({length:23},(_,i)=>({score:12+i%7,video:'NEW'+i}));
  const used=new Set(old.map(x=>x.video).filter(Boolean)),assign=new Map();
  for(const o of old.filter(x=>!x.video)){const c=candidates.find(c=>!used.has(c.video));if(!c)break;used.add(c.video);assign.set(o.slot,c);}
  const result=old.map(o=>assign.has(o.slot)?{...o,...assign.get(o.slot)}:o);
  assert.deepEqual(result.map(r=>r.slot),old.map(o=>o.slot));
  assert.equal(result.filter(x=>x.video).length,50);
  assert.equal(new Set(result.map(r=>r.video)).size,50);
  assert.ok(result.some((r,i)=>r.score!==old[i].score));
});
test('LAB27 update in place, same Android package and original signing family',()=>{
  const stamp=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab27-env.py','utf8');
  const wf=fs.readFileSync('.github/workflows/turbo-lab27-video-first-batch-refill-apk.yml','utf8');
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE="1027"/);
  assert.match(stamp,/MusicLab Turbo LAB 27/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(wf,/application-label:'MusicLab Turbo LAB 27'/);
  assert.match(wf,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49/);
});
