import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const resolver=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt','utf8');
const browser=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const sources=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const check=resolver.slice(resolver.indexOf('internal fun isHardCompatible('),resolver.indexOf('private fun sameVersionIntent('));
const binding=browser.slice(browser.indexOf('suspend fun resolveVideoChunk('),browser.indexOf('fun videoPriorityPool('));
test('DOC 01 no callers gain performer bypass by default',()=>{
 assert.match(check,/documentedPerformerMatch: Boolean = false/);
});
test('DOC 02 title similarity remains a hard gate even for documented versions',()=>{
 assert.match(check,/if \(!titleCompatible\) return false/);
});
test('DOC 03 unintended promotional, lyric or wrong recording types remain filtered',()=>{
 assert.match(check,/BAD_VIDEO_MARKERS\.containsMatchIn\(songTitle\)/);
});
test('DOC 04 live, remix and acoustic intent must still match',()=>{
 assert.match(check,/if \(!sameVersionIntent\(targetTitle, songTitle\)\) return false/);
});
test('DOC 05 duration sanity stays strict and is checked after performer evidence',()=>{
 assert.match(check,/if \(abs\(expected - actual\) > tolerance\) return false/);
});
test('DOC 06 relaxed performer requirement is only the artist-field/video-title comparison',()=>{
 assert.match(check,/!artistFieldMatch && !titleNamesTargetArtist && !documentedPerformerMatch/);
});
test('DOC 07 documentary override requires COVER.INFO source, not generic YouTube',()=>{
 assert.match(binding,/source\?\.equals\("COVER.INFO", ignoreCase = true\) == true/);
 assert.match(binding,/current\.sourceNames\.any \{\s*it\.equals\("COVER.INFO", ignoreCase = true\)/);
});
test('DOC 08 documentary override additionally requires a confirmed musical work relation',()=>{
 assert.match(binding,/current\.workRelationConfirmed/);
 assert.match(sources,/workRelationConfirmed =\s*seed\.directRelation \|\| seed\.relationRole != CoverInfoRelationRole\.NONE/);
});
test('DOC 09 documented YouTube ID must match the exact ID tied to the catalog record',()=>{
 assert.match(binding,/current\.resolvedVideoId\?\.trim\(\) == candidate\.id/);
 assert.match(binding,/documentedPerformerMatch = documentedPerformer/);
});
test('DOC 10 no blind metadata search path is granted performer bypass',()=>{
 assert.ok(!resolver.includes('documentedPerformerMatch: Boolean = true'));
 assert.match(binding,/verified = verifyCandidateSong\(resolved\?\.song, resolved\?\.source\)/);
 assert.match(browser,/\.filter\(::hasPublishableVideo\)\.take\(TURBO_RESULT_QUOTA\)/);
});
