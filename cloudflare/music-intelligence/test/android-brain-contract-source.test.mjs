import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/GeminiAiCoverDiscovery.kt',
  'utf8',
);
const cloud = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt',
  'utf8',
);
const originals = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionSearchEngine.kt',
  'utf8',
);

test('Android candidate model carries Brain metadata without changing stable identity', () => {
  for (const token of [
    'enum class AiBrainDecisionStatus',
    'data class AiBrainSignal',
    'val sameWorkScore: Int? = null',
    'val versionTypeScore: Int? = null',
    'val brainStatus: AiBrainDecisionStatus? = null',
    'val brainAdmission: String? = null',
    'val brainSignals: List<AiBrainSignal> = emptyList()',
  ]) {
    assert.ok(source.includes(token), `missing Android Brain contract token: ${token}`);
  }

  const stableKeyBlock = source.slice(source.indexOf('val stableKey:'), source.indexOf('internal data class AiCoverDiscoveryResult'));
  assert.ok(!stableKeyBlock.includes('sameWorkScore'));
  assert.ok(!stableKeyBlock.includes('brainStatus'));
});

test('Cloud parser preserves Worker Brain metadata and LAB20 client marker', () => {
  for (const token of [
    'sameWorkScore = obj.scoreOrNull("sameWorkScore")',
    'versionTypeScore = obj.scoreOrNull("versionTypeScore")',
    'brainStatus = AiBrainDecisionStatus.fromWire(obj.nullableString("brainStatus"))',
    'brainAdmission = obj.nullableString("brainAdmission")',
    'brainSignals = obj.brainSignals("brainSignals")',
    '"x-musiclab-client", "android-lab20"',
  ]) {
    assert.ok(cloud.includes(token), `missing Cloud Brain parser token: ${token}`);
  }
});

test('Android asks D1 memory before starting new Cover research rounds', () => {
  assert.ok(cloud.includes('suspend fun discoverMemory('), 'missing Cloud memory-only client method');
  assert.ok(cloud.includes('/api/v1/memory/discover'), 'missing memory-only Worker route');
  const memoryIndex = source.indexOf('CloudMusicDiscovery.discoverMemory(');
  const researchIndex = source.indexOf('for (roundGroup in RESEARCH_ROUNDS.chunked');
  assert.ok(memoryIndex >= 0, 'Cover engine does not read learned D1 memory');
  assert.ok(researchIndex >= 0, 'Cover research loop not found');
  assert.ok(memoryIndex < researchIndex, 'D1 memory must be read before new AI research');
});

test('Originali uses learned D1 memory as search plan before generic queries', () => {
  assert.ok(cloud.includes('mode: String = "cover"'), 'memory client must support Cover and Originali modes');
  const memoryIndex = originals.indexOf('CloudMusicDiscovery.discoverMemory(');
  const genericIndex = originals.indexOf('defaultVersionQueries(identity)');
  assert.ok(memoryIndex >= 0, 'Originali does not read learned D1 memory');
  assert.ok(originals.includes('mode = "originals"'), 'Originali memory request does not select originals mode');
  assert.ok(genericIndex >= 0, 'Originali generic query planner not found');
  assert.ok(memoryIndex < genericIndex, 'Originali must consult D1 before generic query planning');
});
