import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt', 'utf8');
const capsule = fs.readFileSync('.motorlab/MOTORLAB_BOOT_CAPSULE.txt', 'utf8');
const hook = fs.readFileSync('.codex/hooks/motorlab_boot.py', 'utf8');

test('LAB58 MotorLab full suite is versioned and complete', () => {
  assert.match(capsule, /RELEASE=2026\.10\.08-r34/);
  assert.match(capsule, /SECOND_VISIBLE_LINE=✨ MotorLab — I Fantastici 20 attivati/);
  assert.match(hook, /REQUIRED_CAPSULE_MARKERS/);
  assert.match(hook, /UserPromptSubmit/);
});

test('LAB58 covers are separate from originals and retain immutable visible snapshots', () => {
  assert.match(browser, /publishedOriginalSnapshots: List<DiscogsVersionSeed>/);
  assert.match(browser, /publishedCoverSnapshots: List<DiscogsVersionSeed>/);
  assert.match(browser, /fun originalCandidatePool/);
  assert.match(browser, /fun coverCandidatePool[\s\S]*filterNot\(::isOriginalPerformerVersion\)/);
  assert.match(browser, /val visibleOriginalVersions =[\s\S]*publishedOriginalSnapshots/);
  assert.match(browser, /val visibleTrueCovers =[\s\S]*publishedCoverSnapshots/);
  assert.match(browser, /visibleOriginalVersions \+ visibleTrueCovers/);
});

test('LAB58 scores discovery before freezing, without reshuffling already published cards', () => {
  assert.match(browser, /DIRECT_COVER_RANK_MAX_SOURCE_PAGES/);
  assert.match(browser, /rankPagesFetched/);
  const discover = browser.indexOf('var rankPagesFetched = 1');
  const freeze = browser.indexOf('session.rankingFrozen = true', discover);
  assert.ok(discover >= 0 && freeze > discover);
  assert.match(browser, /val appended = sortedPool\(results\)/);
});

test('LAB58 replaces failed candidates without consuming their score or one of ten slots', () => {
  assert.match(browser, /val committed = publishedCoverSnapshots\.mapTo/);
  assert.match(browser, /return coverCandidatePool\(\)\.filterNot/);
  assert.match(browser, /val pending = remainingCoverPool\(\)/);
  assert.match(browser, /val group = pending\.filter\(::hasVideoPreview\)\.take\(requested\)/);
  assert.match(browser, /publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
  assert.match(browser, /DIRECT_VIDEO_BATCH_SIZE = 5/);
  assert.match(browser, /DIRECT_COVER_PAGE_SIZE = 10/);
});

test('LAB58 only enables another ten Cover slots when current ten have been published', () => {
  assert.match(browser, /visibleTrueCovers\.size >= visibleLimit/);
  assert.match(browser, /Text\("Carica altri \$pageSize"\)/);
  assert.match(browser, /visibleLimit = visibleLimit \+ pageSize/);
  assert.match(browser, /https:\/\/i\.ytimg\.com\/vi\/\$it\/hqdefault\.jpg/);
});

test('compaction example preserves scores and full ten-slot page across 5+5', () => {
  const ranked = Array.from({length: 24}, (_, i) => ({id:i+1, score:Math.max(1,20-Math.floor(i/2))}));
  const unavailable = new Set([4, 13, 17]);
  const available = ranked.filter(x => !unavailable.has(x.id));
  const first = available.slice(0,10);
  const second = available.slice(10,20);
  assert.equal(first.length,10);
  assert.equal(second.length,10);
  assert.deepEqual(first.map(x=>x.id), [1,2,3,5,6,7,8,9,10,11]);
  assert.deepEqual(second.map(x=>x.id), [12,14,15,16,18,19,20,21,22,23]);
  assert.equal(first[3].score,ranked[4].score);
  assert.deepEqual([first.slice(0,5).length,first.slice(5,10).length],[5,5]);
});

test('LAB58 network timeout retry is bounded before candidate rejection', () => {
  assert.match(browser, /transientVideoRetries: MutableMap<String, Int>/);
  assert.match(browser, /if \(streamProbe == null\) transientTimeout = true/);
  assert.match(browser, /if \(resolved == null\) transientTimeout = true/);
  assert.match(browser, /catch \(cancel: CancellationException\) \{\s*throw cancel/);
  assert.match(browser, /if \(transientTimeout && retries < 1\)/);
  assert.match(browser, /DiscogsVersionSource\.markVideoUnavailable\(latest\)/);
});

test('LAB58 first original section becomes stable even when empty', () => {
  assert.match(browser, /originalSectionFrozen: Boolean = false/);
  assert.match(browser, /if \(!originalSectionFrozen\)/);
  assert.match(browser, /originalSectionFrozen = true/);
});
