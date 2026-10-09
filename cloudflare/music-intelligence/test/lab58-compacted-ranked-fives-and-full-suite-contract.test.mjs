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

test('LAB65 verifies video identities before committing five-song batches', () => {
  const pub = browser.slice(browser.indexOf('suspend fun publishReadyBatches()'), browser.indexOf('suspend fun resolveNextVideoBatch('));
  assert.match(pub, /val ready = settledPrefix/);
  assert.match(pub, /val group = ready\.take\(slots\)/);
  assert.match(pub, /publishedCoverSnapshots = publishedCoverSnapshots \+ group/);
  assert.doesNotMatch(pub, /val group = pending/);
});

test('LAB58 only enables another ten Cover slots when current ten have been published', () => {
  assert.doesNotMatch(browser, /visibleTrueCovers\.size >= visibleLimit/);
  assert.match(browser, /Text\("Carica altri \$pageSize"\)/);
  assert.match(browser, /visibleLimit = visibleLimit \+ pageSize/);
  assert.match(browser, /https:\/\/i\.ytimg\.com\/vi\/\$it\/hqdefault\.jpg/);
});

test('LAB65 compacts only verified scores into two stable five-song groups', () => {
  const songs = Array.from({length:24},(_,i)=>({id:i+1,score:20-Math.floor(i/2),valid:![4,13,17].includes(i+1)}));
  const ready = songs.filter(song=>song.valid);
  assert.deepEqual(ready.slice(0,5).map(x=>x.id), [1,2,3,5,6]);
  assert.deepEqual(ready.slice(5,10).map(x=>x.id), [7,8,9,10,11]);
  assert.ok(ready.every(x=>x.valid));
  assert.ok(ready.every((x,i)=>i===0||x.score<=ready[i-1].score));
});

test('LAB58 network timeout retry is bounded before candidate rejection', () => {
  assert.match(browser, /transientVideoRetries: MutableMap<String, Int>/);
  assert.doesNotMatch(browser, /getStreamUrl\(/);
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
