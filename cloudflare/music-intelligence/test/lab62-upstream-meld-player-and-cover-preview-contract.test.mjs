import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';

const player = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt','utf8');
const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const cover = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const env = fs.readFileSync('uab-project.env','utf8');
const preferences = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/intelligence/MusicIntelligence.kt','utf8');

test('LAB62 player is byte-for-byte upstream Meld main @ 9cb6105 on 2026-10-08', () => {
  const blob = createHash('sha1')
    .update(Buffer.concat([Buffer.from('blob ' + Buffer.byteLength(player) + '\0'),Buffer.from(player)]))
    .digest('hex');
  assert.equal(blob, '03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
  assert.match(player, /bundle\(\)\.extractor\.extract\(/);
  assert.doesNotMatch(player, /LAB07_FAST_LANE_TIMEOUT_MS|fastExtractor\.extract|fastTokenProvider/);
});

test('LAB62 retains app audio APIs without a competing early player runtime prewarm', () => {
  assert.match(service, /InnerTubeXPlayer\.initialize\(this\)/);
  assert.doesNotMatch(service, /InnerTubeXPlayer\.prepare\(\)/);
  assert.doesNotMatch(service, /InnerTubeXPlayer\.prewarm\(\)/);
  assert.match(service, /fun playQueue\(/);
  assert.match(service, /InnerTubeXPlayer\.playerResponseForPlayback\(/);
  assert.match(service, /fun beginPlaybackPriorityBurst\(/);
});

test('LAB62 logs actual Media3 readiness and buffer events, without claims of first audible sample', () => {
  assert.match(service, /lab62PlaybackStartAtMs/);
  assert.match(service, /lab62BufferingCount\+\+/);
  assert.match(service, /MusicLabLAB62Audio/);
  assert.match(service, /ready_elapsed_ms=%d buffering_count=%d/);
  assert.match(service, /Player\.STATE_BUFFERING/);
  assert.match(service, /Player\.STATE_READY/);
});

test('LAB62 retains AI master switch isolation from the player, and Qobuz remains opt-in', () => {
  assert.match(preferences, /val master = ds\.get\(MusicAiEngineEnabledKey, true\)/);
  assert.match(preferences, /backgroundMetadata = master &&/);
  assert.doesNotMatch(service, /MusicAiEngineEnabledKey/);
  assert.match(service, /cachedEnableQobuz = startupPrefs!!\[EnableQobuzKey\] \?: false/);
});

test('LAB62 uses same Cover image slot: video thumbnail first, trusted temporary release image second', () => {
  const card = cover.slice(cover.indexOf('private fun DiscogsVersionCard('));
  const model = card.slice(card.indexOf('if (showVideoPreview) {'),card.indexOf('} else {',card.indexOf('if (showVideoPreview) {')));
  assert.match(model, /model = stableArtworkUrl/);
  assert.match(cover, /fun stableArtworkFor\(seed: DiscogsVersionSeed\)/);
  assert.equal((model.match(/AsyncImage\(/g)||[]).length,1);
  assert.match(cover, /DIRECT_COVER_PAGE_SIZE = 10/);
});

test('LAB62 preserves in-place install identity and increases update version', () => {
  assert.match(env,/UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="6502"/);
  assert.match(env,/UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
});
