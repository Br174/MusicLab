import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const read = file => fs.readFileSync(file, 'utf8');
const b=read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const swipe=read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSwipeBridge.kt');
const thumb=read('app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt');
const menu=read('app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt');
const nav=read('app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt');
const env=read('uab-project.env');
const meld=read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const section=(a,z)=>b.slice(b.indexOf(a),b.indexOf(z,b.indexOf(a)));

test('LAB65 searches original performer recordings before the top scored covers', () => {
  assert.match(b,/DIRECT_ORIGINAL_PRIORITY_COUNT = 10/);
  const prep=section('fun videoPreparationPool():', 'fun preparationReadyVideoCount():');
  assert.match(prep,/if \(!originalSectionFrozen\)/);
  assert.match(prep,/originalCandidatePool\(\)\.take\(DIRECT_ORIGINAL_PRIORITY_COUNT\)/);
  assert.match(prep,/coverCandidatePool\(\)/);
  assert.match(b,/DIRECT_VIDEO_PARALLELISM = 2/);
  assert.match(b,/DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS = 3_800L/);
});

test('LAB64 gives extra bounded attempts to strongest 20-point candidates without discarding low ranks',()=>{
  const policy=section('fun autoVideoAttemptBudget(seed:', 'fun preparationReadyVideoCount():');
  assert.match(policy,/seed\.confidenceScore >= 17 -> 4/);
  assert.match(policy,/seed\.confidenceScore >= 10 -> 3/);
  assert.match(policy,/else -> 2/);
  assert.match(policy,/session\.automaticVideoAttempts\[seed\.fingerprint\]/);
  assert.match(b,/session\.automaticVideoAttempts\[seed\.fingerprint\] =/);
  assert.match(b,/DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS = 3_000L/);
});

test('LAB65 seals originals exactly once before showing verified cover groups',()=>{
  const pub=section('suspend fun publishReadyBatches()', 'suspend fun resolveNextVideoBatch(');
  assert.match(pub,/publishedOriginalSnapshots = candidates\.filter\(::hasPublishableVideo\)/);
  assert.match(pub,/originalSectionFrozen = true/);
  assert.match(pub,/DIRECT_ORIGINAL_FIRST_GATE_MS/);
  assert.match(b,/val visibleOriginalVersions =/);
  assert.match(b,/val visibleTrueCovers =/);
});

test('LAB64 retires obsolete Originali action, route and standalone screen',()=>{
  assert.doesNotMatch(menu,/text = "Originali"/);
  assert.match(menu,/text = "Cover"/);
  assert.doesNotMatch(nav,/OriginalVersionNavigationBridge|OriginalVersionScreen/);
  assert.equal(fs.existsSync('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt'),false);
  assert.equal(fs.existsSync('app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionNavigationBridge.kt'),false);
});

test('LAB64 swipe is Cover-specific and loads next recording without changing manual page count',()=>{
  assert.match(thumb,/CoverSwipeBridge\.trySwipe\(/);
  assert.match(thumb,/isPlayerExpanded\(\)/);
  assert.match(swipe,/fun activate\(screen: String, videoId: String\)/);
  assert.match(swipe,/currentCoverVideoId != actualVideoId/);
  assert.match(swipe,/fun stop\(screen: String\)/);
  assert.match(swipe,/fun clear\(screen: String\)/);
  const handler=section('fun swipeCover(direction:', 'LaunchedEffect(');
  assert.match(handler,/if \(mode != DiscogsDirectMode\.COVER/);
  assert.match(handler,/orderedResults\(results\)/);
  assert.match(handler,/play\(next\)/);
  assert.match(handler,/loadPage\(criteria, currentPage \+ 1, replace = false\)/);
  assert.doesNotMatch(handler,/visibleLimit =|session\.visibleLimit =/);
  assert.match(b,/CoverSwipeBridge\.stop\(sessionKey\)/);
  assert.match(b,/CoverSwipeBridge\.activate\(sessionKey, selectedId\)/);
});

test('LAB64 keeps LAB63 stable artwork and exact upstream Meld resolver',()=>{
  assert.match(b,/CoverPlaybackMemory\.pinVideoArtwork\(context, selectedId, selectedArtwork\)/);
  assert.match(b,/CoverPlaybackMemory\.saveVerifiedVideo/);
  const content=Buffer.from(meld);
  const blob=createHash('sha1').update(Buffer.concat([
    Buffer.from('blob '+content.length+'\0'),content
  ])).digest('hex');
  assert.equal(blob,'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.turbo01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1002"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-turbo-family-02-test-reuses-lab-test-key"/);
});
