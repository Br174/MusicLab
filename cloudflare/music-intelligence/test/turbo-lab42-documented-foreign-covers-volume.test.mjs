import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p, 'utf8');
const sources = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const direct = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const gate = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');
const recognized = sources.split('val identityAccepted =')[1].split('val chronologicallyPossible')[0];
const admitted = direct.split('fun modeAcceptsSeed(seed: DiscogsVersionSeed): Boolean {')[1].split('fun isOriginalPerformerVersion(')[0];

test('LAB42: translated works are not rejected only because of their title', () => {
  assert.match(recognized, /anchoredWorkRelation/);
  assert.match(recognized, /strictTitleMatch \|\|[\s\S]*?anchoredWorkRelation \|\|/);
  assert.match(recognized, /candidate\.workRelationConfirmed &&/);
});

test('LAB42: only proven structural sources count as same-work evidence', () => {
  for(const provider of ['MusicBrainz','SecondHandSongs','COVER.INFO']) {
    assert.match(recognized, new RegExp('source\\.equals\\("'+provider.replace('.','\\.')+'"'));
  }
  assert.doesNotMatch(recognized, /source\.equals\("Spotify"|source\.equals\("Apple"|source\.equals\("LRCLIB"/);
  assert.match(gate, /seed\.workRelationConfirmed &&/);
});

test('LAB42: no equivalence from identical title or score alone', () => {
  assert.match(admitted,/CoverSameWorkGate\.hasWorkIdentity\(/);
  assert.ok(admitted.indexOf('CoverSameWorkGate.hasWorkIdentity(') <
      admitted.indexOf('if (titleOk) return true'));
  assert.ok(!gate.includes('confidenceScore >='));
});

test('LAB42: documentary same work allows translated titles after safety gate', () => {
  assert.match(admitted,/if \(CoverSameWorkGate\.hasDocumentaryRelation\(seed\)\) return true/);
  assert.ok(admitted.indexOf('if (CoverSameWorkGate.hasDocumentaryRelation(seed)) return true') <
      admitted.indexOf('if (titleOk) return true'));
});

test('LAB42: the other audio/movie/version gates are unchanged', () => {
  assert.match(direct,/TURBO_RESULT_QUOTA = 50/);
  assert.match(direct,/TURBO_COVER_INFO_WAIT_MS = 8_200L/);
  assert.match(direct,/backgroundWorkBlocked\(\)/);
});

test('LAB42: 50 documentary candidates retained, title-only providers at 24', () => {
  assert.match(direct,/val verifiedWorkLane = diagnostic\.name in setOf\(/);
  assert.match(direct,/\.take\(if \(verifiedWorkLane\) TURBO_RESULT_QUOTA else 24\)/);
  assert.match(direct,/streamingCandidates\.trySend\(fresh\)/);
});

test('LAB42: provider status remains exposed without inventing availability', () => {
  assert.match(direct,/sourceStatus\[diagnostic\.name\] = diagnostic/);
  assert.match(direct,/"SecondHandSongs"/);
  assert.match(direct,/tempo scaduto o servizio non disponibile/);
});

test('LAB42: previous source integration stays active', () => {
  assert.match(sources,/SecondHandSongsCoverSource\.lookup\(title, artist\)/);
  assert.match(sources,/MusicBrainzCoverSource\.lookup\(cleanTitle, cleanArtist\)/);
  assert.match(sources,/discoverCoverInfo\(cleanTitle, cleanArtist, mode/);
});
