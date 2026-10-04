import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = (p) => fs.readFileSync(p, 'utf8');

const player = read('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const uab = read('uab-project.env');

test('LAB41 restores LAB07-style fast-first playback before the modern fallback', () => {
  assert.match(player, /LAB07_FAST_LANE_TIMEOUT_MS = 1_800L/);
  const fast = player.indexOf('extractionBundle.fastExtractor.extract');
  const full = player.indexOf('extractionBundle.extractor.extract', fast + 1);
  assert.ok(fast >= 0, 'fast extractor must exist');
  assert.ok(full > fast, 'modern full extractor must remain after fast lane');
  assert.match(player, /fastStream \?: requireNotNull/);
});

test('LAB41 fast lane never opens WebView or asks for PoToken', () => {
  const start = player.indexOf('private val fastTokenProvider');
  const end = player.indexOf('private val tokenProvider', start);
  const block = player.slice(start, end);
  assert.match(block, /providers = emptySet\(\)/);
  assert.match(block, /usesWebView = false/);
  assert.match(block, /PoTokenResult\? = null/);
});

test('LAB41 preserves the full modern PoToken resolver as fallback', () => {
  assert.match(player, /providers = setOf\(PoTokenProviderKind\.WEB_BOTGUARD\)/);
  assert.match(player, /usesWebView = true/);
  assert.match(player, /poTokenGenerator\.getWebClientPoToken/);
});

test('LAB41 remains isolated from LAB40 and MADRE', () => {
  assert.match(uab, /UAB_FILE_VERSION="LAB41"/);
  assert.match(uab, /UAB_OUTPUT_NAME="MusicLab-LAB41"/);
  assert.match(uab, /UAB_APPLICATION_ID_BASE="it\.verlezza\.musiclab\.lab41"/);
  assert.match(uab, /UAB_APP_NAME_BASE="MusicLab LAB41"/);
});
