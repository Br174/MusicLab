import test from 'node:test';
import assert from 'node:assert/strict';
import {
  admitCoverCandidate,
  evaluateEvidence,
  buildDualLanguageQueries,
  buildLanguageMissions,
  statusForScore,
  thresholdForCoverageMode,
  selectCoverageGaps,
} from '../src/brain.js';

test('missing source support never auto-rejects a plausible candidate', () => {
  const result = evaluateEvidence({
    positives: [{ kind: 'composer_match', strength: 'strong' }, { kind: 'title_match', strength: 'medium' }],
    negatives: [{ kind: 'musicbrainz_missing', strength: 'none' }],
  });
  assert.notEqual(result.status, 'REJECTED');
  assert.ok(result.sameWorkScore >= 50);
});

test('Two-Key rule admits title + composer with a different performer', () => {
  const result = admitCoverCandidate({
    originalArtist: 'Jimmy Fontana',
    candidateArtist: 'Milva',
    signals: [
      { kind: 'title_match', strength: 'medium' },
      { kind: 'composer_match', strength: 'strong' },
    ],
  });
  assert.equal(result.admitted, true);
});

test('Two-Key rule does not promote title + album only', () => {
  const result = admitCoverCandidate({
    originalArtist: 'Jimmy Fontana',
    candidateArtist: 'Someone Else',
    signals: [
      { kind: 'title_match', strength: 'medium' },
      { kind: 'album_match', strength: 'weak' },
    ],
  });
  assert.equal(result.admitted, false);
});

test('cover rule rejects same main performer from cover lane', () => {
  const result = admitCoverCandidate({
    originalArtist: 'Jimmy Fontana',
    candidateArtist: 'Jimmy Fontana',
    signals: [
      { kind: 'title_match', strength: 'medium' },
      { kind: 'composer_match', strength: 'strong' },
    ],
  });
  assert.equal(result.admitted, false);
  assert.equal(result.reason, 'same_performer');
});

test('dual-language queries preserve original title and use IT+EN terminology', () => {
  const queries = buildDualLanguageQueries({ title: 'Il mondo', artist: 'Jimmy Fontana' });
  assert.ok(queries.some(q => q.includes('"Il mondo" cover version')));
  assert.ok(queries.some(q => q.includes('"Il mondo" versione')));
  assert.ok(queries.every(q => q.includes('Il mondo')));
});

test('language missions search adaptations beyond literal translation', () => {
  const missions = buildLanguageMissions({
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    targetLanguages: ['francese', 'spagnolo'],
    writers: ['Gianni Meccia'],
  });
  assert.equal(missions.length, 2);
  assert.ok(missions[0].strategies.includes('related_work'));
  assert.ok(missions[0].strategies.includes('writer_credit'));
  assert.ok(missions[0].strategies.includes('known_adaptation'));
  assert.notEqual(missions[0].strategies[0], 'literal_translation');
});

test('status mapping keeps uncertain candidates instead of deleting them', () => {
  assert.equal(statusForScore(92), 'APPROVED');
  assert.equal(statusForScore(72), 'PROBABLE');
  assert.equal(statusForScore(38), 'UNCERTAIN');
  assert.equal(statusForScore(10), 'REJECTED');
});

test('coverage mode thresholds are monotonic and Tutto remains bounded', () => {
  assert.equal(thresholdForCoverageMode('precisa'), 85);
  assert.equal(thresholdForCoverageMode('selezionata'), 70);
  assert.equal(thresholdForCoverageMode('ampia'), 50);
  assert.equal(thresholdForCoverageMode('esplora'), 30);
  assert.equal(thresholdForCoverageMode('tutto'), 15);
});

test('coverage map returns only unsearched gaps', () => {
  const gaps = selectCoverageGaps({
    desired: ['francese', 'spagnolo', 'portoghese', 'tribute'],
    completed: ['spagnolo'],
    inFlight: ['tribute'],
  });
  assert.deepEqual(gaps, ['francese', 'portoghese']);
});
