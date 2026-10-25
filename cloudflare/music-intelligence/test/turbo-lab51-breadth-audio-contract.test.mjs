import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const read = f => readFileSync(root + f, 'utf8');
const browser = read('DiscogsDirectVersionBrowser.kt');
const discovery = read('CoverDiscoverySources.kt');
const mb = read('MusicBrainzCoverSource.kt');
const recovery = browser.split('fun startDocumentaryRecovery()')[1].split('fun pauseCoverBackgroundForPlayback()')[0];

test('LAB45 original first search and LAB49 playback priority preserved', () => {
 assert.match(read('CoverRoundSearchPlan.kt'), /if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
 assert.match(browser, /while \(playbackIsNormallyPlaying\(\) \|\|/);
 assert.match(browser, /coverRecoveryJob\?\.cancel\(\)/);
 assert.match(recovery, /isCoverAudioAtRisk\(\) == true/);
});
test('Recovery works in every subsequent manual round, never mixes generations', () => {
 assert.match(recovery, /val expectedRound = coverRoundNumber/);
 assert.doesNotMatch(recovery, /coverRoundNumber != 1/);
 assert.match(recovery, /coverRoundNumber != expectedRound/);
 assert.match(recovery, /coverRound = expectedRound/);
 assert.match(recovery, /CoverRoundStorage\.save\(context, sessionKey, expectedRound, saved\)/);
});
test('Already published recordings and actual playback videos cannot return as duplicates', () => {
 assert.match(recovery, /DiscogsVersionSource\.recordingIdentityKey\(seed\) !in occupied/);
 assert.match(recovery, /seed\.resolvedVideoId\.trim\(\) !in usedVideoIds/);
 assert.match(recovery, /CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
 assert.match(recovery, /preserveInputOrder = true/);
});
test('MusicBrainz deep rounds use different windows, bounded validated work crawling', () => {
 assert.match(mb, /deepRound: Int = 1/);
 assert.match(mb, /val offset = \(deepRound - 1\)\.coerceIn\(0, 12\) \* 35/);
 assert.match(mb, /validatedWorks >= DEEP_MAX_ANCHORED_WORKS/);
 assert.match(discovery, /deepRound = coverRound/);
 assert.match(discovery, /deep-round-\$\{coverRound\.coerceAtLeast\(1\)\}/);
});
test('SecondHandSongs does not run on LAB51 deep rounds', () => {
 assert.match(discovery, /if \(deepRecovery\) \{[\s\S]*?"SecondHandSongs", false, 0, "Escluso dal recupero approfondito"/);
 assert.match(discovery, /\} else if \(mode != DiscogsDirectMode\.COVER/);
});
test('Cancelled or timed-out deep crawl never reports false completion', () => {
 assert.match(recovery, /documentaryRecoveryComplete = recovered != null/);
});
