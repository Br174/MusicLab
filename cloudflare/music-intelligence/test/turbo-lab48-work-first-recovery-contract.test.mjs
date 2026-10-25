import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const base = 'app/src/main/kotlin/com/metrolist/music/ui/component/';
const source = p => fs.readFileSync(base+p, 'utf8');
const discovery = source('CoverDiscoverySources.kt');
const mb = source('MusicBrainzCoverSource.kt');
const shs = source('SecondHandSongsCoverSource.kt');
const screen = source('DiscogsDirectVersionBrowser.kt');
const quality = source('CoverSameWorkGate.kt');

test('LAB48 preserves the initial LAB45 discovery and never activates deep browse by default', () => {
  assert.match(discovery,/deepRecovery: Boolean = false/);
  assert.match(mb,/deepRecovery: Boolean = false/);
  assert.match(shs,/deepRecovery: Boolean = false/);
  assert.match(discovery,/if \(matchesWork && !deepRecovery\)/);
  assert.match(screen,/coverRound = if \(appendVerifiedRound\) coverRoundNumber else 1/);
  assert.match(source('CoverRoundSearchPlan.kt'),/if \(round <= 1 \|\| title\.isBlank\(\)\) return emptyList\(\)/);
});

test('LAB48 extends work-first documentary catalogue pagination only during recovery', () => {
  assert.match(mb,/lookupByWorkTitle\(/);
  assert.match(mb,/browseValidatedWork\(/);
  assert.match(mb,/if \(deepRecovery\) 700 else MAX_BROWSED_RECORDINGS/);
  assert.match(shs,/if \(deepRecovery\) 24 else MAX_WORK_VERSION_DETAILS/);
  assert.match(shs,/relationCovers \+ workResult\.covers/);
});

test('LAB48 retains source-complete early evidence when slower providers exceed deadline', () => {
  assert.match(screen,/earlyEvidence\.addAll\(rows\)/);
  assert.match(screen,/recovered\?\.candidates\.orEmpty\(\)/);
});

test('LAB48 auto recovery requires sparse first-round snapshot and caps result count', () => {
  assert.match(screen,/val originallySparse = publishedCoverSnapshots\.size < 15/);
  assert.match(screen,/val expectedRound = coverRoundNumber/);
  assert.match(screen,/val accepted = CoverLab53Publication\.fill\(/);
  assert.match(screen,/if \(additional > 0\)/);
});

test('LAB48 never admits title-only Youtube or cloud matches as documentary proof', () => {
  assert.match(screen,/CoverSameWorkGate\.hasDocumentaryRelation\(seed\)/);
  assert.match(quality,/"cover\.info", "secondhandsongs", "whosampled", "musicbrainz"/);
  assert.match(quality,/seed\.workRelationConfirmed &&/);
  assert.match(discovery,/workRelationConfirmed = false/);
});

test('LAB48 uses a single existing deduplication and separately searches video', () => {
  assert.match(screen,/DiscogsVersionSource\.dedupeVersions\(/);
  assert.match(screen,/preserveInputOrder = true/);
  assert.match(screen,/!session\.turboVideoComplete/);
  assert.doesNotMatch(screen,/session\.playReadyVideoIds\.add\(seed\.resolvedVideoId/);
});
