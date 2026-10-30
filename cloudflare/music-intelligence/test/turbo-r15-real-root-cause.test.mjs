import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const source=readFileSync(root+'CoverDiscoverySources.kt','utf8');
const admission=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const browser=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const policy=readFileSync(root+'CoverPreparationPolicy.kt','utf8');

test('COVER.INFO relation target must not be the song identity or cause phantom duplicates', () => {
  assert.ok(source.includes('val songId = match.groupValues[1]'),
    'Song identity must come from /en/song/<songId>, NOT repeated ?initial-details=workId');
  assert.ok(!source.includes('val songId = relationMatch?.groupValues'),
    'Root relation target was incorrectly replacing every distinct cover song ID');
});
test('a transient YouTube metadata failure must not embargo that video for the entire round', () => {
  const publish=browser.split('suspend fun publish(candidate: CoverSourceCandidate) {')[1]
    ?.split('fun resolveOriginalWhenUseful()')[0];
  assert.ok(publish);
  assert.ok(!publish.includes('session.searchedLeadRounds[CoverPriorityOrder.key(candidate)] = thisRound'),
    'A failed metadata request must have per-ID retry window, not round-long poisoning');
  assert.ok(publish.includes('session.metadataRetryAfter'));
});
test('verified metadata batch should still include multiple IDs during normal playback', () => {
  const metadata=policy.split('fun metadataIds(')[1]?.split('fun mayRefreshVisible')[0]||'';
  assert.ok(metadata.includes('take('));
  assert.ok(!metadata.includes('if (protectPlayback) listOf(first)'),
    'Single-id queue calls starve hundreds of documented videos while music plays');
});
test('first round may corroborate a verified catalogue recording against documented writer credits', () => {
  assert.ok(admission.includes('catalogue-credited-performance'));
  assert.ok(admission.includes('matchedWorkWriter'));
});
