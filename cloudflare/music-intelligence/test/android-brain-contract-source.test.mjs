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
const coverHub = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverHubSearchEngine.kt',
  'utf8',
);
const originalScreen = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt',
  'utf8',
);
const coverScreen = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
  'utf8',
);
const youtubeWeb = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/YouTubeWebSearch.kt',
  'utf8',
);
const compactCoverScreen = coverScreen.replace(/\s+/g, ' ');
const compactOriginalScreen = originalScreen.replace(/\s+/g, ' ');

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

test('Originali remembered adaptations are matched against their remembered title, not only the canonical title', () => {
  assert.ok(originals.includes('searchRememberedOriginalVersions('), 'missing remembered-version resolver');
  assert.ok(
    originals.includes('targetTitle = exactBaseTitle(candidate.title)'),
    'remembered adapted titles must be validated against the candidate title such as El mondo',
  );
  assert.ok(
    originals.includes('query = "${candidate.title} ${candidate.artist}".trim()'),
    'remembered candidate title and artist must drive the playback locator query',
  );
});

test('Cover session performs one MusicBrainz lookup and reuses its neutral evidence before playback resolution', () => {
  assert.ok(
    coverScreen.includes('var sourceEvidence: AiCoverSourceEvidence? = null'),
    'Cover session must retain one evidence snapshot',
  );
  assert.equal(
    coverScreen.split('MusicBrainzCoverSource.lookup(').length - 1,
    1,
    'MusicBrainz must be looked up once per Cover session, never per batch/candidate',
  );
  assert.ok(
    coverScreen.includes('sourceEvidence.attachToAiAccepted(brainInitial.versions)'),
    'initial AI candidates must receive source evidence before playback resolution',
  );
  assert.ok(
    coverScreen.includes('sourceEvidence.attachToAiAccepted(discoveredBatch)'),
    'expanded AI candidates must reuse the same source evidence',
  );
  assert.ok(
    coverScreen.includes('sourceEvidence.attachToAiAccepted(recovered)'),
    'recovery AI candidates must reuse the same source evidence',
  );
  assert.ok(
    coverScreen.includes('sourceEvidence = null'),
    'invalidating a Cover session must clear stale source evidence',
  );
  const enrichIndex = coverScreen.indexOf('sourceEvidence.attachToAiAccepted(brainInitial.versions)');
  const playbackIndex = coverScreen.indexOf('AiCoverSearchEngine.resolveCandidates(');
  assert.ok(enrichIndex >= 0 && playbackIndex > enrichIndex, 'source evidence must be attached before YouTube playback resolution');
});

test('Cover applies coverage to the complete tab before review split and pagination without joining discovery keys', () => {
  assert.ok(coverScreen.includes('var selectedCoverageMode by remember(sessionKey) { mutableStateOf(AiCoverageMode.DEFAULT) }'));
  assert.ok(compactCoverScreen.includes('val coverageVisibleResults = AiCoverageFilter.visibleItems( selectedTabResults, selectedCoverageMode,'));
  assert.ok(compactCoverScreen.includes('val confirmedResults = orderedCoverageResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.APPROVED }'));
  assert.ok(compactCoverScreen.includes('val reviewResults = orderedCoverageResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.UNCERTAIN }'));
  assert.ok(compactCoverScreen.includes('val selectedResults = orderedCoverageResults.filter { it.candidate.brainStatus != AiBrainDecisionStatus.UNCERTAIN && it.candidate.brainStatus != AiBrainDecisionStatus.APPROVED }'));
  assert.ok(coverScreen.includes('AiCoverageSelector(\n                                selected = selectedCoverageMode'));
  const projectionIndex = coverScreen.indexOf('val coverageVisibleResults = AiCoverageFilter.visibleItems(');
  const reviewIndex = coverScreen.indexOf('val reviewResults = orderedCoverageResults.filter');
  const paginationIndex = coverScreen.indexOf('selectedResults.take(visibleResultCount)');
  assert.ok(projectionIndex >= 0 && reviewIndex > projectionIndex);
  assert.ok(paginationIndex > reviewIndex);
  const discoveryEffectStart = coverScreen.indexOf('LaunchedEffect(\n        sessionKey,');
  const discoveryEffectBody = coverScreen.indexOf('    ) {', discoveryEffectStart);
  assert.ok(discoveryEffectStart >= 0 && discoveryEffectBody > discoveryEffectStart);
  const discoveryKeys = coverScreen.slice(discoveryEffectStart, discoveryEffectBody);
  assert.ok(!discoveryKeys.includes('selectedCoverageMode'));
});

test('Originali preserves Brain metadata, separates review and filters before pagination', () => {
  assert.ok(
    coverHub.includes('val brainCandidate: AiCoverCandidate? = null'),
    'CoverHubResult must carry the optional Brain candidate that produced a playable result',
  );
  assert.ok(
    originals.includes('result.copy(brainCandidate = candidate)'),
    'remembered Originali playback results must retain their Brain candidate metadata',
  );
  assert.ok(
    originalScreen.includes('var selectedOriginalCoverageMode by remember(request.currentYouTubeId) { mutableStateOf(AiCoverageMode.PRECISE) }'),
    'Originali must own an independent PRECISE coverage state',
  );
  assert.ok(
    compactOriginalScreen.includes('val reviewVersions = selectedVersions.filter { it.brainCandidate?.brainStatus == AiBrainDecisionStatus.UNCERTAIN }'),
    'Originali must split UNCERTAIN versions into the review queue',
  );
  assert.ok(
    compactOriginalScreen.includes('selectedVersions.filterNot { it.brainCandidate?.brainStatus == AiBrainDecisionStatus.UNCERTAIN }, selectedOriginalCoverageMode,'),
    'Originali must apply coverage to already-resolved non-review results',
  );
  assert.ok(
    originalScreen.includes('AiCoverageSelector(\n                                selected = selectedOriginalCoverageMode'),
    'Originali coverage selector must be wired into its UI independently from Cover',
  );

  const reviewIndex = originalScreen.indexOf('val reviewVersions = selectedVersions.filter');
  const projectionIndex = originalScreen.indexOf('val filteredSelectedVersions = AiCoverageFilter.visibleItems(');
  const paginationIndex = originalScreen.indexOf('filteredSelectedVersions.take(visibleVersionCount)');
  assert.ok(reviewIndex >= 0 && projectionIndex > reviewIndex, 'Originali review split must happen before coverage projection');
  assert.ok(paginationIndex > projectionIndex, 'Originali coverage projection must happen before pagination');

  const discoveryEffectStart = originalScreen.indexOf('LaunchedEffect(\n        request.currentYouTubeId,');
  const discoveryEffectBody = originalScreen.indexOf('    ) {', discoveryEffectStart);
  assert.ok(discoveryEffectStart >= 0 && discoveryEffectBody > discoveryEffectStart, 'Originali discovery LaunchedEffect not found');
  const discoveryKeys = originalScreen.slice(discoveryEffectStart, discoveryEffectBody);
  assert.ok(!discoveryKeys.includes('selectedOriginalCoverageMode'), 'changing Originali coverage must not relaunch AI discovery');
});


test('Cover and Originali keep independent resolver pipelines under the same engine', () => {
  assert.ok(
    coverScreen.includes('AiCoverFlowResolver.discoverInitial('),
    'Cover must enter through AiCoverFlowResolver',
  );
  assert.ok(
    originalScreen.includes('OriginalVersionSearchEngine.identifyOriginal('),
    'Originali must enter through OriginalVersionSearchEngine',
  );
  assert.ok(
    !originalScreen.includes('AiCoverFlowResolver.discoverInitial('),
    'Originali must never reuse the Cover discovery pipeline',
  );
  assert.ok(
    !coverScreen.includes('OriginalVersionSearchEngine.findExpandedVersions('),
    'Cover must never reuse the Originali expansion pipeline',
  );
});


test('LAB23 Cover keeps regular youtube.com recovery plus fast title-only first paint', () => {
  assert.ok(
    youtubeWeb.includes('https://www.youtube.com/youtubei/v1/search'),
    'Cover WEB lane must call regular youtube.com instead of relabeling music.youtube.com video search',
  );
  assert.ok(youtubeWeb.includes('"clientName", "WEB"'), 'regular YouTube search must use the WEB client context');
  assert.ok(
    coverScreen.includes('selectedCoverageMode != AiCoverageMode.ALL || backgroundLoading'),
    'Tutto must own an explicit exhaustive-locator trigger',
  );
  assert.ok(
    coverScreen.includes('exhaustive = true'),
    'Tutto must retry all still-unresolved non-rejected candidates with the exhaustive locator',
  );
  assert.ok(
    coverScreen.includes('CoverResultChip("Studio"') && coverScreen.includes('CoverResultChip("Tutto"'),
    'Cover category chips must expose Studio and Tutto',
  );
  assert.ok(
    coverScreen.includes('mutableStateOf(AiCoverTab.ALL)'),
    'LAB23 must open Cover with Tutto selected',
  );
  assert.ok(
    coverScreen.includes('MusicLabTitleSearch.fast('),
    'LAB23 must paint the first Cover rows from the lightweight title-only lane',
  );
  assert.ok(
    coverScreen.includes('SpotifyMusicAssist.assistCover('),
    'LAB23 must use Spotify as a positive-only Cover assist',
  );
  assert.ok(
    coverScreen.includes('CoverSortChip("Qualità"') &&
      coverScreen.includes('CoverSortChip("Anno ↑"') &&
      coverScreen.includes('CoverSortChip("Anno ↓"'),
    'LAB23 must expose quality and both year orderings without changing categories',
  );
});

test('Cover confirmation moves immediately before cloud persistence and keeps rollback on failure', () => {
  const optimisticIndex = coverScreen.indexOf('updateBrainCandidate(optimisticCandidate)');
  const persistIndex = coverScreen.indexOf('CloudMusicDiscovery.saveBrainDecision(');
  assert.ok(optimisticIndex >= 0 && persistIndex > optimisticIndex, 'Conferma must update the visible section before cloud persistence');
  assert.ok(
    coverScreen.includes('updateBrainCandidate(result.candidate.copy(brainStatus = previousStatus))'),
    'failed persistence must roll the optimistic confirmation back safely',
  );
  assert.ok(coverScreen.includes('CoverSectionTitle("Confermate")'), 'approved versions need a visible confirmed section');
});
