import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const browser = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const queries = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt','utf8');
const admission = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt','utf8');
const settings = readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/CoverCompilationSettings.kt','utf8');
const memory = JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));

test('approved canonical behavior memory has retired every listed conflicting rule',()=>{
  for(const c of memory.conflictDispositions){
    assert.equal(memory.rules.find(r=>r.id===c.oldRule).status,'retired',c.oldRule);
    assert.equal(memory.rules.find(r=>r.id===c.replacementRule).status,'active',c.replacementRule);
  }
  assert.equal(new Set(memory.rules.map(r=>r.id)).size,memory.rules.length);
});

test('initial direct YouTube search happens before retired poster first engine',()=>{
  const primary=browser.indexOf('LAB66 canonical Behavior Memory');
  const legacy=browser.indexOf('// A further Cover round never clears');
  assert.ok(primary>0 && legacy>primary);
  assert.match(browser,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
  assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
  assert.match(browser,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) \{/);
  assert.match(browser,/session\.turboFinished = true \/\/ legacy per-poster video\/recovery schedulers disabled/);
});

test('YouTube thumbnail bound to verified video id, no editorial Turbo fallback',()=>{
  assert.match(browser,/YouTube\.queue\(videoIds = listOf\(id\)\)/);
  assert.match(browser,/metadata\.id != id/);
  assert.match(browser,/https:\/\/i\.ytimg\.com\/vi\/\$\{video\.id\}\/hqdefault\.jpg/);
  assert.match(browser,/if \(modernCoverCard\) \{/);
  assert.match(browser,/add\("https:\/\/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg"\)/);
  assert.match(browser,/session\.playReadyVideoIds\.add\(id\)/);
});

test('only one manually controlled same-round orange or next-round green action',()=>{
  assert.match(browser,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
  assert.match(browser,/Color\(0xFF258E4E\)/);
  assert.match(browser,/Color\(0xFFF39A27\)/);
  assert.match(browser,/session\.youtubeQueryCursor = cursor \+ offset \+ 1/);
  assert.match(browser,/else if \(roundPlanComplete\) \{/);
  assert.doesNotMatch(browser.slice(browser.indexOf('Text(if (roundPlanComplete) "Cerca ancora"'),browser.indexOf('Text(if (roundPlanComplete) "Cerca ancora"')+400),/20\%|10\%/);
});

test('progressive musical confidence, official first plus foreign coverinfo, no quota percentages',()=>{
  assert.match(admission,/round <= 1 -> strong/);
  assert.match(admission,/round == 2 -> medium/);
  assert.match(admission,/round == 3 -> weak/);
  assert.match(admission,/else -> containsTitle \|\| exactTitle \|\| relation/);
  assert.match(queries,/private val official/);
  assert.match(queries,/foreign language cover/);
  assert.match(browser,/onEarlyVideoCandidates = \{ candidates ->/);
  assert.match(browser,/Precisione stimata:/);
  assert.match(browser,/\.toString\(\) \+ "%"/);
});

test('source switches prevent remote Cloud and archive reads in new Turbo path',()=>{
  assert.match(settings,/rememberPreference\(MusicLabCoverArchiveEnabledKey, true\)/);
  assert.match(settings,/rememberPreference\(MusicLabCloudEnabledKey, true\)/);
  assert.match(browser,/includeDocumentary && coverCloudEnabled &&\s*coverArchiveEnabled/);
  assert.match(browser,/aiConfig = null, \/\/ remote Cloud sources are separate gated lanes/);
  assert.match(browser,/val effectiveCloudEndpoint = if \(coverCloudEnabled\)/);
});

test('no Cloud D1 write/delete, native player or protected production changes from this search plan',()=>{
  assert.doesNotMatch(browser.slice(browser.indexOf('LAB66 canonical Behavior Memory'),browser.indexOf('// A further Cover round never clears')),/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
});
