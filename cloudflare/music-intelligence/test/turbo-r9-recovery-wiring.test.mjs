import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
const root=new URL('../../../app/src/main/kotlin/com/metrolist/music/ui/component/',import.meta.url);
const read=n=>readFileSync(new URL(n,root),'utf8');
const browser=read('DiscogsDirectVersionBrowser.kt');
test('display deadline cannot cancel documentary or video producers',()=>{
 assert.doesNotMatch(browser,/if \(presentation\.exhausted\(thisRound\)\) \{\s*documents\.cancel/);
});
test('native documentary lane obtains track details rather than publishing search summaries',()=>{
 const native=browser.slice(browser.indexOf('fun offerCandidates('));
 const start=native.indexOf('val discogs =');
 const lane=native.slice(start,native.indexOf('val youtube = launch',start));
 assert.match(lane,/enrichSeedMetadata/);
 assert.match(lane,/CoverLabTurboRoundAdmission\.candidate/);
});
test('native recovery persists and restores the actual work credit context',()=>{
 assert.match(browser,/saved\.referenceCredits/);
 assert.match(read('CoverRoundStorage.kt'),/referenceCredits/);
});
test('native source coordinator remains wired to existing audio/scroll governor',()=>{
 assert.match(browser,/fun audioBusy\(\) = backgroundWorkBlocked\(\) \|\| playerConnection\?\.service\?\.isCoverAudioAtRisk\(\) == true/);
 assert.match(browser,/while \(isActive && \(audioBusy\(\) \|\| listState\.isScrollInProgress\)\) delay\(120L\)/);
});
