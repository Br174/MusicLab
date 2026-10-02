import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const legacyWorker = readFileSync('cloudflare/music-intelligence/src/index.js', 'utf8');
const coverScreen = readFileSync(
  'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
  'utf8',
);
const resolverPath = 'app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverFlowResolver.kt';

test('cover memory with only canonical original never short-circuits fresh discovery', () => {
  assert.ok(
    legacyWorker.includes("mode === 'cover' ? cached.versions.length > 0"),
    'Cover initial discovery must require learned versions; original-only D1 memory is not a completed Cover search',
  );
});

test('Cover uses a dedicated multi-source flow resolver', () => {
  assert.ok(existsSync(resolverPath), 'AiCoverFlowResolver.kt must exist');
  const resolver = readFileSync(resolverPath, 'utf8');
  for (const token of [
    'internal object AiCoverFlowResolver',
    'MusicBrainzCoverSource.lookup(',
    'SecondHandSongsCoverSource.lookup(',
    'WhoSampledCoverSource.lookup(',
    'AiBrainDecisionStatus.UNCERTAIN',
    'sameItalianTitle(',
  ]) {
    assert.ok(resolver.includes(token), `missing Cover Flow Resolver contract token: ${token}`);
  }
  assert.ok(
    coverScreen.includes('AiCoverFlowResolver.discoverInitial('),
    'Cover screen must route initial discovery through AiCoverFlowResolver',
  );
  assert.ok(
    coverScreen.includes('AiCoverFlowResolver.discoverExpandedBatches('),
    'Cover screen must route expansion through AiCoverFlowResolver',
  );
});

test('Cover UI starts directly from Originale di partenza without the AI identity banner', () => {
  assert.ok(
    !coverScreen.includes('CoverSectionTitle("Identificato dall\'AI")'),
    'the Identificato dall AI banner is diagnostic/editorial data and must not be rendered',
  );
  assert.ok(
    !coverScreen.includes("L'AI decide le informazioni editoriali. YouTube e YouTube Music servono esclusivamente a trovare una riproduzione."),
    'the explanatory AI banner copy must be removed from the visible Cover layout',
  );
  assert.ok(
    coverScreen.includes('CoverSectionTitle("Originale di partenza")'),
    'Originale di partenza must remain the first visible editorial section',
  );
});
