import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const read = f => readFileSync(root + f, 'utf8');
const browser = read('DiscogsDirectVersionBrowser.kt');
const discovery = read('CoverDiscoverySources.kt');
const mb = read('MusicBrainzCoverSource.kt');
const shs = read('SecondHandSongsCoverSource.kt');

test('LAB50 preserves LAB45 first-round speed and LAB49 playback priority', () => {
  assert.match(read('CoverRoundSearchPlan.kt'), /if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
  assert.match(browser, /coverRound = if \(appendVerifiedRound\) coverRoundNumber else 1/);
  assert.match(browser, /fun pauseCoverBackgroundForPlayback\(\) \{[\s\S]*?coverRecoveryJob\?\.cancel\(\)/);
  assert.match(browser, /fun resumeCoverBackgroundAfterPlaybackBurst\(\)/);
  assert.match(browser, /startDocumentaryRecovery\(\)/);
});

test('LAB50 waits without burning provider budget during playback, only runs idle', () => {
  const a = browser.indexOf('fun startDocumentaryRecovery()');
  const b = browser.indexOf('fun pauseCoverBackgroundForPlayback()', a);
  const helper = browser.slice(a, b);
  assert.match(helper, /while \(playbackIsNormallyPlaying\(\) \|\|/);
  assert.ok(helper.indexOf('while (playbackIsNormallyPlaying() ||') < helper.indexOf('withTimeoutOrNull(90_000L)'));
  assert.match(helper, /backgroundWorkBlocked\(\)/);
  assert.match(helper, /maxNetworkFanOut = 2/);
  assert.match(helper, /forceRefresh = false/);
  assert.match(helper, /documentaryRecoveryComplete = true/);
});

test('LAB50 expands beyond 15 confirmed rows while preserving a 50-slot quota', () => {
  assert.match(browser, /publishedOriginalSnapshots\.size \+ publishedCoverSnapshots\.size >= TURBO_RESULT_QUOTA/);
  assert.match(browser, /val freeSlots = \(TURBO_RESULT_QUOTA -/);
  assert.match(browser, /if \(additional > 0\)/);
  assert.match(browser, /preserveInputOrder = true/);
  assert.match(browser, /CoverRoundStorage\.save\(context, sessionKey, 1, saved\)/);
});

test('LAB50 incrementally adds verified findings from each source, not video guesses', () => {
  assert.match(browser, /onEarlyVideoCandidates = \{ rows ->[\s\S]*?appendVerifiedSource\(rows\)/);
  assert.match(browser, /onSourceCompleted = \{ rows, _, _ ->[\s\S]*?appendVerifiedSource\(rows\)/);
  assert.match(browser, /CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
  assert.match(discovery, /deepRecovery && candidate\.workRelationConfirmed/);
  assert.match(discovery, /source\.equals\("MusicBrainz", true\)/);
  assert.match(discovery, /source\.equals\("SecondHandSongs", true\)/);
  assert.match(discovery, /source\.equals\("COVER.INFO", true\)/);
  assert.match(discovery, /workRelationConfirmed = false/);
});

test('LAB50 selects only artist-confirmed work identities, never mixes homonyms', () => {
  assert.match(mb, /var bestAnchoredWork: MusicBrainzLookup\? = null/);
  assert.match(mb, /if \(!deepRecovery\) return result/);
  assert.match(mb, /if \(!workMatchesOriginal\(distinctRecordings, wantedTitle, wantedArtist\)\)/);
  assert.match(shs, /var bestVerifiedWork: SecondHandSongsLookup\? = null/);
  assert.match(shs, /originalArtist\.isNotBlank\(\)/);
  assert.match(shs, /TitleMeaningResolver\.sameArtist\(version\.artist, originalArtist\)/);
});

test('LAB50 preserves source limits, caches and avoids compulsory paid services', () => {
  assert.match(mb, /MIN_REQUEST_INTERVAL_MS = 1_100L/);
  assert.match(mb, /if \(deepRecovery\) 700 else MAX_BROWSED_RECORDINGS/);
  assert.match(shs, /if \(deepRecovery\) 24 else MAX_WORK_VERSION_DETAILS/);
  assert.match(discovery, /rememberDiscovery\(key, now, outcome\)/);
});
