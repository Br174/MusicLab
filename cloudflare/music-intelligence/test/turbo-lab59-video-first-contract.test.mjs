import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const screen=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const pub=readFileSync(root+'CoverLab55VisibleCandidates.kt','utf8');

test('LAB65: covers require a verified video before entering the visible list',()=>{
 assert.ok(pub.includes('if (!video.matches(Regex("[A-Za-z0-9_-]{11}"))) continue'));
 assert.ok(pub.includes('if (verifiedVideoIds != null && video !in verifiedVideoIds) continue'));
 assert.ok(pub.includes('if (!seen.add(identity) || !videos.add(video)) continue'));
 assert.equal(pub.includes('evidenceEnoughWithoutVideo'),false);
 assert.equal(screen.split('verifiedVideoIds = session.playReadyVideoIds,').length-1,5);
});
test('LAB59: reuse existing hard-compatible metadata verifier before video publication',()=>{
 const part=screen.split('val videoFirstVerifier = launch {')[1]?.split('// LAB21: removed legacy')[0];
 assert.ok(part,'Video-first worker exists');
 assert.ok(part.includes('resolveVideoChunk(pending)'));
 assert.ok(part.includes('sealPostersOnce()'));
 assert.ok(part.includes('sealSecondPostersOnce()'));
 assert.ok(part.includes('withTimeoutOrNull(minOf(2_800L, remainingMs))'));
 assert.equal(part.includes('player.prepare()'),false);
});
test('LAB59: direct YouTube thumbnail only for modern Turbo cards',()=>{
 const art=screen.split('fun stableArtworkFor(seed: DiscogsVersionSeed): String? =')[1];
 assert.ok(art.includes('if (isTurbo && mode == DiscogsDirectMode.COVER)'));
 assert.ok(art.includes('https://i.ytimg.com/vi/$it/hqdefault.jpg'));
 const fallback=screen.split('val posterFallbacks = remember(')[1]?.split('var posterIndex by remember')[0];
 assert.ok(fallback);
 const modern=fallback.split('if (modernCoverCard) {')[1]?.split('} else {')[0];
 assert.ok(modern.includes('https://i.ytimg.com/vi/$id/hqdefault.jpg'));
 assert.equal(modern.includes('seed.coverUrl'),false);
 assert.equal(modern.includes('stableArtworkUrl'),false);
});
test('LAB59: final publisher never emits documentary-only poster quota',()=>{
 assert.ok(screen.includes('val finalRanked = withContext(Dispatchers.Default)'));
 assert.ok(screen.includes('seed.resolvedVideoId?.trim().orEmpty() in session.playReadyVideoIds'));
 assert.ok(screen.includes('val firstBlock = initialOriginals +'));
});
