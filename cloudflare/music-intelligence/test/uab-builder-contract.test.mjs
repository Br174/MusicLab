import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const uab = readFileSync('tools/universal-apk-builder/build-apk.sh', 'utf8');

test('UAB self-heals a missing standard Android debug keystore before Gradle build', () => {
  for (const token of [
    'ensure_debug_keystore()',
    'debug.keystore',
    'keytool -genkeypair',
    '-alias androiddebugkey',
    '-storepass android',
    '-keypass android',
    'ensure_debug_keystore',
  ]) {
    assert.ok(uab.includes(token), `missing UAB debug-keystore recovery token: ${token}`);
  }

  const hookIndex = uab.indexOf('run_hook "Pre-build"');
  const keystoreCallIndex = uab.indexOf('\nensure_debug_keystore\n');
  const gradleIndex = uab.indexOf('./gradlew "$TASK"');
  assert.ok(hookIndex >= 0, 'UAB pre-build hook not found');
  assert.ok(keystoreCallIndex > hookIndex, 'debug keystore recovery must run after project pre-build hook');
  assert.ok(gradleIndex > keystoreCallIndex, 'debug keystore recovery must run before Gradle build');
});
