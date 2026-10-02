import test from 'node:test';
import assert from 'node:assert/strict';
import { decorateDiscoveryPayload } from '../src/worker-v20.js';
import { normalizeSourceResult } from '../src/source-router.js';
import { LAB20_BENCHMARK_CASES } from './fixtures/lab20-benchmark-corpus.mjs';

const REQUIRED_SCENARIOS = [
  'il-mondo',
  'few-covers',
  'international-english-hit',
  'adapted-title-differs',
  'many-originals',
  'uncertain-case',
  'poor-musicbrainz',
];

test('LAB20 permanent benchmark contains every approved regression scenario', () => {
  const scenarios = LAB20_BENCHMARK_CASES.map(item => item.scenario);
  assert.deepEqual(scenarios, REQUIRED_SCENARIOS);
  assert.equal(new Set(LAB20_BENCHMARK_CASES.map(item => item.id)).size, LAB20_BENCHMARK_CASES.length);
  assert.ok(LAB20_BENCHMARK_CASES.every(item => item.title && item.artist && item.mode));
});

test('LAB20 benchmark keeps plausible musical candidates instead of deleting weak evidence', () => {
  for (const item of LAB20_BENCHMARK_CASES.filter(entry => entry.payload)) {
    const result = decorateDiscoveryPayload(item.payload, item.mode);
    assert.ok(
      result.versions.length >= item.expect.minRetained,
      `${item.id}: expected at least ${item.expect.minRetained} retained candidates`,
    );
    assert.equal(
      result.versions.some(version => version.brainStatus === 'REJECTED' && item.expect.mustNotRejectArtists?.includes(version.artist)),
      false,
      `${item.id}: a protected plausible candidate was rejected`,
    );
    if (item.expect.requireUncertain === true) {
      assert.ok(result.versions.some(version => version.brainStatus === 'UNCERTAIN'), `${item.id}: UNCERTAIN review lane missing`);
    }
  }
});

test('LAB20 poor-source benchmark treats missing MusicBrainz support as neutral, never as a veto', () => {
  const item = LAB20_BENCHMARK_CASES.find(entry => entry.scenario === 'poor-musicbrainz');
  const result = normalizeSourceResult('musicbrainz', item.musicbrainzResult);
  assert.equal(result.neutral, true);
  assert.deepEqual(result.negativeEvidence, []);
});
