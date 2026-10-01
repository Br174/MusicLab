import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as brainWorker from '../src/worker-v20.js';

const coverScreen = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
  'utf8',
);
const originalScreen = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt',
  'utf8',
);
const cloudClient = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt',
  'utf8',
);

test('Brain decision identity resolves the same D1 work/version keys without exposing database ids', () => {
  assert.equal(typeof brainWorker.decisionIdentityKeys, 'function');
  assert.equal(typeof brainWorker.saveBrainDecision, 'function');
  const keys = brainWorker.decisionIdentityKeys({
    originalTitle: 'Il mondo',
    originalArtist: 'Jimmy Fontana',
    candidate: {
      title: 'Il mondo',
      artist: 'Milva',
      category: 'cover',
      language: 'it',
    },
  });
  assert.deepEqual(keys, {
    workSearchKey: 'il mondo|jimmy fontana',
    versionKey: 'cover|milva|il mondo|it',
  });
});

test('Android cloud client can persist a manual Brain decision and request deeper verification', () => {
  for (const token of [
    'suspend fun saveBrainDecision(',
    '/api/v1/brain/decision',
    'suspend fun verifyCandidate(',
    'Verifica meglio esclusivamente il candidato',
  ]) {
    assert.ok(cloudClient.includes(token), `missing Task 5 cloud client token: ${token}`);
  }
});

test('Cover exposes UNCERTAIN review actions and raw title search without AI', () => {
  for (const token of [
    'Da verificare',
    'Ascolta',
    'Conferma',
    'Rifiuta',
    'Verifica meglio',
    'SearchRoutes.resultRoute(result.candidate.title)',
    'CloudMusicDiscovery.saveBrainDecision(',
    'CloudMusicDiscovery.verifyCandidate(',
  ]) {
    assert.ok(coverScreen.includes(token), `missing Cover Task 5 token: ${token}`);
  }
});

test('Originali exposes independent UNCERTAIN review actions and raw title search without AI', () => {
  for (const token of [
    'Da verificare',
    'Ascolta',
    'Conferma',
    'Rifiuta',
    'Verifica meglio',
    'SearchRoutes.resultRoute(result.song.title)',
    'CloudMusicDiscovery.saveBrainDecision(',
    'CloudMusicDiscovery.verifyCandidate(',
  ]) {
    assert.ok(originalScreen.includes(token), `missing Originali Task 5 token: ${token}`);
  }
});
