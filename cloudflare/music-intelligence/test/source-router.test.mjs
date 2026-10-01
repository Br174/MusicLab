import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildSourcePlan,
  buildWorkSignature,
  normalizeSourceResult,
  evidenceSignalsForCandidate,
} from '../src/source-router.js';

test('source plan starts from memory then free structured sources', () => {
  assert.deepEqual(
    buildSourcePlan(),
    ['d1', 'musicbrainz', 'wikidata'],
  );
  assert.deepEqual(
    buildSourcePlan({ secondHandSongs: true, discogs: true, lastfm: true, webGap: true }),
    ['d1', 'musicbrainz', 'wikidata', 'discogs', 'lastfm', 'secondhandsongs', 'web_gap'],
  );
});

test('work signature preserves canonical title, aliases, translations and credits', () => {
  const signature = buildWorkSignature({
    title: ' Il mondo ',
    artist: 'Jimmy Fontana',
    aliases: ['Il Mondo', 'The World'],
    translatedTitles: ['El mundo', 'El món', 'El mundo'],
    writers: ['Gianni Meccia'],
    composers: ['Jimmy Fontana', 'Lilli Greco'],
    iswc: 'T-005.001.002-0',
    musicbrainzWorkId: 'work-123',
    language: 'it',
    year: 1965,
  });

  assert.equal(signature.canonicalTitle, 'Il mondo');
  assert.equal(signature.originalArtist, 'Jimmy Fontana');
  assert.deepEqual(signature.aliases, ['Il Mondo', 'The World']);
  assert.deepEqual(signature.translatedTitles, ['El mundo', 'El món']);
  assert.deepEqual(signature.writers, ['Gianni Meccia']);
  assert.deepEqual(signature.composers, ['Jimmy Fontana', 'Lilli Greco']);
  assert.equal(signature.iswc, 'T-005.001.002-0');
  assert.equal(signature.musicbrainzWorkId, 'work-123');
});

test('unavailable or no-match source is neutral and never becomes negative evidence', () => {
  for (const result of [
    normalizeSourceResult('musicbrainz', { status: 'unavailable', error: 'timeout' }),
    normalizeSourceResult('wikidata', { status: 'no_match' }),
  ]) {
    assert.equal(result.neutral, true);
    assert.deepEqual(result.negativeEvidence, []);
    assert.deepEqual(result.candidates, []);
  }
});

test('structured identifiers and matching credits become positive evidence only', () => {
  const signals = evidenceSignalsForCandidate({
    source: 'musicbrainz',
    workIdMatches: true,
    iswcMatches: true,
    writerMatches: true,
    composerMatches: true,
    aliasMatches: true,
  });
  assert.deepEqual(signals, [
    { kind: 'musicbrainz_work_match', strength: 'very_strong', source: 'musicbrainz' },
    { kind: 'iswc_match', strength: 'very_strong', source: 'musicbrainz' },
    { kind: 'writer_match', strength: 'strong', source: 'musicbrainz' },
    { kind: 'composer_match', strength: 'strong', source: 'musicbrainz' },
    { kind: 'alias_match', strength: 'medium', source: 'musicbrainz' },
  ]);
});

test('source absence never emits different-work confirmation by inference', () => {
  const result = normalizeSourceResult('discogs', { status: 'no_match', candidates: [] });
  assert.equal(result.negativeEvidence.some(item => item.kind === 'different_work_confirmed'), false);
});
