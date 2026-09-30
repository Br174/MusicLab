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
