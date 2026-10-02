import test from 'node:test';
import assert from 'node:assert/strict';
import { buildBrainFocus, buildBrainPlanPayload, decorateDiscoveryPayload } from '../src/worker-v20.js';

test('brain focus preserves the original title and asks in Italian and English', () => {
  const focus = buildBrainFocus({ title: 'Il mondo', artist: 'Jimmy Fontana', mode: 'cover' });
  assert.match(focus, /Il mondo/);
  assert.match(focus, /cover version/i);
  assert.match(focus, /versione|reinterpretazione/i);
  assert.match(focus, /non tradurre letteralmente/i);
  assert.match(focus, /fonte.*evidenza/i);
});

test('Brain planner exposes COSA, DOVE and COME in one contract', () => {
  const plan = buildBrainPlanPayload({
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    mode: 'cover',
    aliases: ['The World'],
    translatedTitles: ['El mundo'],
    writers: ['Gianni Meccia'],
    composers: ['Jimmy Fontana'],
    iswc: 'T-005.001.002-0',
    musicbrainzWorkId: 'work-123',
    languages: ['inglese', 'spagnolo'],
  });

  assert.equal(plan.workSignature.canonicalTitle, 'Il mondo');
  assert.deepEqual(plan.workSignature.translatedTitles, ['El mundo']);
  assert.deepEqual(plan.sourcePlan, ['d1', 'musicbrainz', 'wikidata']);
  assert.ok(plan.dualLanguageQueries.some(query => /cover/i.test(query)));
  assert.deepEqual(plan.languageMissions.map(item => item.language), ['inglese', 'spagnolo']);
});

test('optional source lanes are explicit and never silently mandatory', () => {
  const plan = buildBrainPlanPayload({
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    sources: { secondHandSongs: true, discogs: true, lastfm: true, webGap: true },
  });
  assert.deepEqual(
    plan.sourcePlan,
    ['d1', 'musicbrainz', 'wikidata', 'discogs', 'lastfm', 'secondhandsongs', 'web_gap'],
  );
});

test('decorator retains weak but plausible cover as UNCERTAIN instead of deleting it', () => {
  const payload = decorateDiscoveryPayload({
    original: { title: 'Il mondo', artist: 'Jimmy Fontana', credits: { composers: ['Carlo Pes'] } },
    versions: [{ title: 'El mundo', artist: 'Artista X', category: 'straniera', credits: {} }],
  }, 'cover');
  assert.equal(payload.versions.length, 1);
  assert.equal(payload.versions[0].brainStatus, 'UNCERTAIN');
});

test('decorator gives stronger score when title and composer match', () => {
  const payload = decorateDiscoveryPayload({
    original: { title: 'Il mondo', artist: 'Jimmy Fontana', credits: { composers: ['Carlo Pes'] } },
    versions: [{ title: 'Il mondo', artist: 'Milva', category: 'cover', credits: { composers: ['Carlo Pes'] } }],
  }, 'cover');
  assert.ok(payload.versions[0].sameWorkScore >= 70);
  assert.notEqual(payload.versions[0].brainStatus, 'REJECTED');
});
