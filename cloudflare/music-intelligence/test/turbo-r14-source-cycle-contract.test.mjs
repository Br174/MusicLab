import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const source = readFileSync(root + 'CoverDiscoverySources.kt', 'utf8');
const browser = readFileSync(root + 'DiscogsDirectVersionBrowser.kt', 'utf8');
const presentation = readFileSync(root + 'CoverRoundPresentation.kt', 'utf8');

test('all permitted discovery lanes remain active independently of COVER.INFO', () => {
  for (const token of [
    'val coverInfo = async(Dispatchers.IO)',
    'val musicBrainzLookup =',
    'cachedLane("Apple/iTunes")',
    'cachedLane("Spotify")',
    'cachedLane("Last.fm")',
    'cachedLane("LRCLIB")',
    'onEarlyVideoCandidates(quick)',
    'onSourceCompleted(lane.first, lane.second, year)',
  ]) assert.ok(source.includes(token), token);
  for (const token of [
    'val discogs = discogsProducer?.takeIf { it.isActive }',
    'val youtube = launch {',
    'val cloud = cloudProducer?.takeIf { it.isActive }',
    'CoverPriorityOrder.bindDocumentedVideo(raw, pending.snapshot())',
  ]) assert.ok(browser.includes(token), token);
});

test('SecondHandSongs is truly standby in Turbo before any network lookup', () => {
  const lane = source.split('val secondHandSongs = async(Dispatchers.IO) {')[1]?.split('val deepRound =')[0];
  assert.ok(lane);
  assert.ok(lane.indexOf('if (turbo)') >= 0);
  assert.ok(lane.indexOf('if (turbo)') < lane.indexOf('discoverSecondHandSongs('));
  assert.ok(lane.includes('"Standby: fonte non interrogata"'));
  assert.ok(source.includes('cachedLane("Apple/iTunes")'));
  assert.ok(source.includes('cachedLane("Spotify")'));
});

test('the single active video lookup must not defer hundreds of other candidates', () => {
  const index = browser.indexOf('val next = pending.next(rankTitle, rankOriginal, thisRound');
  assert.ok(index > 0);
  const before = browser.slice(index - 380, index);
  assert.ok(before.includes('if (linkingJob?.isActive == true)'), before);
  assert.ok(before.includes('delay(55L)'));
  assert.ok(!before.includes('pending.defer(next)'));
});

test('cycle-time publishing preserves verified-only staging and the 10-20-20 bounds', () => {
  assert.ok(presentation.includes('const val INITIAL_SIZE = 10'));
  assert.ok(presentation.includes('const val BLOCK_SIZE = 20'));
  assert.ok(presentation.includes('const val ROUND_SIZE = 50'));
  assert.ok(presentation.includes('fun takeTimedCycle(): List<T>'));
  assert.ok(browser.includes('appendVisible(presentation.takeTimedCycle())'));
  assert.ok(browser.includes('if (!presentation.stage(thisRound, admitted)) return'));
  assert.ok(browser.includes('CoverLabTurboRoundAdmission.evaluate(candidate'));
});
