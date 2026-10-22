import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = p => fs.readFileSync(p,'utf8');
const sources = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt');
const provider = read('app/src/main/kotlin/com/metrolist/music/ui/component/SecondHandSongsCoverSource.kt');
const gate = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverSameWorkGate.kt');
const browser = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const srcGate = sources.split('private fun discoverSecondHandSongs(')[1].split('private fun discoverMusicBrainz(')[0];

test('LAB41: native SecondHandSongs provider is actually used in Cover discovery',()=>{
  assert.match(sources,/val secondHandSongs = async\(Dispatchers.IO\)/);
  assert.match(sources,/SecondHandSongsCoverSource\.lookup\(title, artist\)/);
  assert.match(sources,/secondHandSongs\.await\(\)/);
  assert.match(browser,/"SecondHandSongs"/);
});

test('LAB41: structured work ID required for admitting a SecondHandSongs performance',()=>{
  assert.match(srcGate,/version\.workId\.isBlank\(\)/);
  assert.match(srcGate,/workRelationConfirmed = true/);
  assert.match(srcGate,/sources = listOf\("SecondHandSongs"\)/);
  assert.match(gate,/"secondhandsongs"/);
  assert.match(gate,/seed\.workRelationConfirmed/);
});

test('LAB41: identical title without matched reference performer cannot anchor a work',()=>{
  assert.match(provider,/artistScore >= 0\.45/);
  assert.match(provider,/TitleMeaningResolver\.sameArtist\(ref\.artist, artist\)/);
  assert.doesNotMatch(provider,/searchArtist\.isBlank\(\) \|\| artistScore >= 0\.24 \|\| titleScore >= 0\.94/);
  assert.match(provider,/originalArtist\.isNotBlank\(\)/);
  assert.match(provider,/lookupByWorkTitle\(title, artist\)/);
  assert.match(provider,/TitleMeaningResolver\.sameArtist\(version\.artist, originalArtist\)/);
});

test('LAB41: no SHS query without a singer or in Original mode',()=>{
  assert.match(sources,/mode != DiscogsDirectMode\.COVER \|\| cleanArtist\.isBlank\(\)/);
  assert.match(sources,/Interprete di riferimento necessario/);
});

test('LAB41: SHS rate limit and access errors do not turn into admitted covers',()=>{
  assert.match(srcGate,/lookup\.status == SecondHandSongsStatus\.OK/);
  assert.match(srcGate,/else emptyList\(\)/);
  assert.match(srcGate,/SecondHandSongsStatus\.NO_MATCH/);
});

test('LAB41: original MusicBrainz/COVER.INFO and user UI remain enabled',()=>{
  assert.match(sources,/MusicBrainzCoverSource\.lookup\(cleanTitle, cleanArtist\)/);
  assert.match(sources,/discoverCoverInfo\(cleanTitle, cleanArtist, mode\)/);
  assert.match(browser,/TURBO_RESULT_QUOTA = 50/);
  assert.match(browser,/Modifier\.weight\(1f\)\.height\(56\.dp\)/);
});

test('LAB41: do not restore the LAB39 title-only admission bug',()=>{
  const admit = browser.split('fun modeAcceptsSeed(seed: DiscogsVersionSeed): Boolean {')[1].split('fun isOriginalPerformerVersion(')[0];
  assert.ok(admit.indexOf('CoverSameWorkGate.hasWorkIdentity(')<admit.indexOf('if (titleOk) return true'));
  assert.match(admit,/lockedArtist\.ifBlank \{ resolvedOriginalArtist \}/);
});

test('LAB41: no new keys, cloud writes, paid API or audio stream probing',()=>{
  assert.doesNotMatch(srcGate,/apiKey|cloudEndpoint|create|update|api\/v1\/write|audioStream|openRouter/i);
  assert.match(sources,/maxNetworkFanOut/);
  assert.match(sources,/sourceGate\.withPermit/);
});
