import test from 'node:test';
import assert from 'node:assert/strict';
import { buildBrainFocus, decorateDiscoveryPayload } from '../src/worker-v20.js';

test('brain focus preserves the original title and asks in Italian and English', () => {
  const focus = buildBrainFocus({ title: 'Il mondo', artist: 'Jimmy Fontana', mode: 'cover' });
  assert.match(focus, /Il mondo/);
  assert.match(focus, /cover version/i);
  assert.match(focus, /versione|reinterpretazione/i);
  assert.match(focus, /non tradurre letteralmente/i);
  assert.match(focus, /fonte.*evidenza/i);
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
