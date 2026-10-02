import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const app = readFileSync('app/src/main/kotlin/com/metrolist/music/App.kt', 'utf8');
const innerTubePlayer = readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt', 'utf8');
const musicService = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
const coverage = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverageMode.kt', 'utf8');
const coverScreen = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt', 'utf8');
const coverResolver = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt', 'utf8');
const compactCoverScreen = coverScreen.replace(/\s+/g, ' ');

test('app startup prepares playback without running full extractor prewarm or WebView token work', () => {
  const prepareIndex = innerTubePlayer.indexOf('suspend fun prepare()');
  const fullPrewarmIndex = innerTubePlayer.indexOf('suspend fun prewarm()');
  assert.ok(prepareIndex >= 0, 'InnerTubeXPlayer must expose a lightweight startup preparation path');
  assert.ok(fullPrewarmIndex > prepareIndex, 'lightweight preparation must remain distinct from full prewarm');
  const prepareBlock = innerTubePlayer.slice(prepareIndex, fullPrewarmIndex);
  assert.ok(
    prepareBlock.includes('bundle().cipherService.initialize()'),
    'lightweight preparation must initialize only the cipher runtime',
  );
  assert.ok(
    !prepareBlock.includes('extractor.prewarm()'),
    'lightweight preparation must never invoke extractor prewarm or PO-token/WebView work',
  );
  assert.ok(
    app.includes('InnerTubeXPlayer.prepare()'),
    'App startup must use lightweight player preparation',
  );
  assert.ok(
    !app.includes('InnerTubeXPlayer.prewarm()'),
    'App startup must not launch full extractor prewarm because it can mint PO tokens through WebView on the main thread',
  );
});

test('playback starts below two seconds while preserving a strong rebuffer reserve', () => {
  assert.ok(
    musicService.includes('private const val PLAYBACK_START_BUFFER_MS = 1_250'),
    'startup buffer contract must be 1.25 seconds',
  );
  assert.ok(
    musicService.includes('private const val PLAYBACK_REBUFFER_MS = 4_000'),
    'rebuffer contract must remain 4 seconds to preserve the device stutter fix',
  );
  assert.ok(
    musicService.includes('.setBufferDurationsMs(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS)'),
    'ExoPlayer must use the LAB20 startup/rebuffer contract',
  );
});

test('Tutto coverage really includes every non-rejected Brain score including UNCERTAIN', () => {
  assert.ok(coverage.includes('ALL(0, "Tutto")'), 'Tutto must not impose a hidden score floor');
  assert.ok(
    compactCoverScreen.includes('val coverageVisibleResults = AiCoverageFilter.visibleItems( selectedTabResults, selectedCoverageMode,'),
    'coverage must project the complete selected tab before status splitting',
  );
  assert.ok(
    compactCoverScreen.includes('val reviewResults = coverageVisibleResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.UNCERTAIN }'),
    'UNCERTAIN results must respect the selected coverage mode instead of bypassing it',
  );
  assert.ok(
    compactCoverScreen.includes('val confirmedResults = coverageVisibleResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.APPROVED }'),
    'confirmed results must be derived from the same coverage projection',
  );
  assert.ok(
    compactCoverScreen.includes('val selectedResults = coverageVisibleResults.filter { it.candidate.brainStatus != AiBrainDecisionStatus.UNCERTAIN && it.candidate.brainStatus != AiBrainDecisionStatus.APPROVED }'),
    'unconfirmed main results must be derived from the same coverage projection',
  );
  assert.ok(
    coverScreen.includes('exhaustive = true'),
    'Tutto must actively exhaust the playback locator for unresolved candidates',
  );
});

test('playback locator requires exact candidate-title identity after technical cleanup', () => {
  assert.ok(
    coverResolver.includes('internal fun isPlaybackTitleCompatible('),
    'resolver must expose a deterministic title identity guard for regression tests',
  );
  assert.ok(
    coverResolver.includes('if (!isPlaybackTitleCompatible(candidate, song.title)) return 0'),
    'resolver score must reject a YouTube song whose canonical title is not the AI candidate title',
  );
});
