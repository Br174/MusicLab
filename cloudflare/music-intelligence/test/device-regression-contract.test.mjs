import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const app = readFileSync('app/src/main/kotlin/com/metrolist/music/App.kt', 'utf8');
const musicService = readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt', 'utf8');
const coverage = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverageMode.kt', 'utf8');
const coverScreen = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt', 'utf8');
const coverResolver = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt', 'utf8');
const compactCoverScreen = coverScreen.replace(/\s+/g, ' ');

test('playback prewarms extractor without a fixed multi-second delay', () => {
  const prewarmIndex = app.indexOf('InnerTubeXPlayer.prewarm()');
  assert.ok(prewarmIndex >= 0, 'missing InnerTubeX prewarm call');
  const warmBlock = app.slice(Math.max(0, prewarmIndex - 600), prewarmIndex + 120);
  assert.ok(!warmBlock.includes('delay(2500)'), 'prewarm must not wait a fixed 2.5 seconds before starting');
  assert.ok(!warmBlock.includes('while (YouTube.visitorData == null'), 'prewarm must not wait for visitorData before preparing extraction');
  const settingsIndex = app.lastIndexOf('initializeSettings()', prewarmIndex);
  assert.ok(settingsIndex >= 0 && settingsIndex < prewarmIndex, 'prewarm must run after base settings initialize');
});

test('playback keeps enough startup and rebuffer reserve for real mobile streams', () => {
  assert.ok(
    musicService.includes('private const val PLAYBACK_START_BUFFER_MS = 2_000'),
    'startup buffer contract must be 2 seconds',
  );
  assert.ok(
    musicService.includes('private const val PLAYBACK_REBUFFER_MS = 4_000'),
    'rebuffer contract must be 4 seconds',
  );
  assert.ok(
    musicService.includes('.setBufferDurationsMs(50_000, 50_000, PLAYBACK_START_BUFFER_MS, PLAYBACK_REBUFFER_MS)'),
    'ExoPlayer must use the stable LAB20 device buffer contract',
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
    compactCoverScreen.includes('val selectedResults = coverageVisibleResults.filterNot { it.candidate.brainStatus == AiBrainDecisionStatus.UNCERTAIN }'),
    'main results must be derived from the same coverage projection',
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
