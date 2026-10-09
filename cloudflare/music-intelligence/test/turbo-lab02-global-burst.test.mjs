import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const s = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const env = fs.readFileSync('uab-project.env','utf8');
test('Turbo LAB03: one 40-second budget starting on search button', () => {
  assert.ok(s.includes('TURBO_BURST_DEADLINE_MS = 40_000L'));
  assert.ok(s.includes('val turboCommandStartedAt = SystemClock.elapsedRealtime()'));
  assert.ok(s.includes('val stopAt = turboCommandStartedAt + TURBO_BURST_DEADLINE_MS'));
});
test('Turbo LAB03: concurrent collection followed by verification and thumbnail caching', () => {
  for (const name of ['archiveDeferred','memoryDeferred','firstPageDeferred','externalDeferred']) {
    assert.ok(s.includes('val '+name+' = async'));
  }
  assert.ok(s.includes('resolveVideoChunk(pending)'));
  assert.ok(s.includes('context.imageLoader.execute('));
  assert.ok(s.includes('finalRanked = coverCandidatePool()'));
});
test('Turbo LAB03: atomic release, verified maximum 50, unchanged ten per page', () => {
  assert.ok(s.includes('TURBO_RESULT_QUOTA = 50'));
  assert.ok(s.includes('DIRECT_COVER_PAGE_SIZE = 10'));
  assert.ok(s.includes('publishedCoverSnapshots = finalRanked'));
  assert.ok(s.includes('if (mode == DiscogsDirectMode.COVER && (!isTurbo || !loading))'));
  assert.ok(s.includes('.filter(::hasPublishableVideo).take(TURBO_RESULT_QUOTA)'));
});
test('Turbo LAB03: stop all background searches, independent update identity', () => {
  assert.ok(s.includes('verificationJob?.cancel()'));
  assert.ok(s.includes('paginationJob?.cancel()'));
  assert.ok(s.includes('videoPreloadJob?.cancel()'));
  assert.ok(s.includes('CloudMusicDiscovery.cancelArchiveRequests()'));
  assert.ok(env.includes('UAB_UPDATE_FAMILY_VERSION_CODE="1003"'));
  assert.ok(env.includes('it.verlezza.musiclab.turbo01'));
});
