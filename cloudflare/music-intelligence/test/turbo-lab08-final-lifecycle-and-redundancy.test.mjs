import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const kotlin=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const source=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const nav=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt','utf8');
const player=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt');
const section=(start,end)=>{
 const a=kotlin.indexOf(start),b=kotlin.indexOf(end,a+start.length);
 assert.ok(a>=0&&b>a,'Missing section '+start);
 return kotlin.slice(a,b);
};
const turbo=section('if (isTurbo && mode == DiscogsDirectMode.COVER) {\n            // Turbo LAB02','\n        } else {\n        searchJob =');
const videoStage=section('// LAB08 Pollicino: from the poster seal','\n        } else {\n        searchJob =');
const play=section('fun play(seed: DiscogsVersionSeed) {','// LAB64: the Cover player can swipe');
const playback=section('suspend fun playResolvedContext(selectedFingerprint: String)','fun runSearch(');
const retry=section('fun retryMissingVideo(seed: DiscogsVersionSeed)','fun play(seed: DiscogsVersionSeed)');
const resume=section('fun resumeCoverBackgroundAfterPlaybackBurst()','fun saveDecision(');
const stagePoster=section('} finally {\n                    // Stop each discovery lane','// LAB08 Pollicino: from the poster seal');

test('AUDIT08 01 all six original-source runners start in parallel before any aggregate await',()=>{
 const all=['archiveDeferred','memoryDeferred','firstPageDeferred','externalDeferred'];
 for(const x of all)assert.match(turbo,new RegExp('val '+x+' = async'));
 assert.ok(turbo.indexOf('val streamingVerifier = launch')<turbo.indexOf('val archiveDeferred = async'));
 assert.match(source,/val coverInfo = async\(Dispatchers.IO\)/);
 assert.match(source,/val iTunes = async\(Dispatchers.IO\)/);
 assert.match(source,/val spotify = async\(Dispatchers.IO\)/);
 assert.match(source,/val lrcLib = async\(Dispatchers.IO\)/);
 assert.match(source,/val musicBrainzLookup =/);
});
test('AUDIT08 02 poster and video deadlines share T0, no sequential 5+10 timer',()=>{
 assert.match(kotlin,/TURBO_BURST_DEADLINE_MS = 5_000L/);
 assert.match(kotlin,/TURBO_VIDEO_DEADLINE_MS = 10_000L/);
 assert.match(turbo,/val stopAt = turboCommandStartedAt \+ TURBO_BURST_DEADLINE_MS/);
 assert.match(videoStage,/val videoStopAt = turboCommandStartedAt \+ TURBO_VIDEO_DEADLINE_MS/);
});
test('AUDIT08 03 exactly one poster publication, up to fifty, never in pages of 5 or 10',()=>{
 assert.match(stagePoster,/val finalRanked = videoLookupPriority\(rankedPosters\.filter/);
 assert.match(stagePoster,/\.take\(TURBO_RESULT_QUOTA\)/);
 assert.match(stagePoster,/publishedCoverSnapshots = finalRanked/);
 assert.match(kotlin,/suspend fun publishReadyBatches\(\) \{[\s\S]*?if \(isTurbo && mode == DiscogsDirectMode\.COVER\) return/);
 assert.match(kotlin,/if \(!isTurbo && nextBlockPool\.isNotEmpty\(\)\)/);
 assert.match(kotlin,/if \(!isTurbo &&\s*next == null/);
});
test('AUDIT08 04 at T+10 final survivors contain only metadata-verified ID video cards, in one batch',()=>{
 assert.match(videoStage,/val finalVideoSnapshots = sortGroup\(\s*publishedCoverSnapshots\.mapNotNull/);
 assert.match(videoStage,/resolved != null && hasPublishableVideo\(resolved\)/);
 assert.match(videoStage,/else \{\s*null\s*\}/);
 assert.match(videoStage,/DirectVersionSort\.RELEVANCE/);
 assert.match(videoStage,/publishedCoverSnapshots = finalVideoSnapshots/);
 assert.equal(videoStage.includes('publishReadyBatches()'),false);
});
test('AUDIT08 05 ID-ready lookup takes precedence; tie sorts by 20→1',()=>{
 assert.match(kotlin,/fun videoLookupPriority\(source:[\s\S]*?compareByDescending<DiscogsVersionSeed> \{ !it\.resolvedVideoId\.isNullOrBlank\(\) \}[\s\S]*?\.thenByDescending \{ it\.confidenceScore \}/);
 assert.equal((kotlin.match(/\.let\(::videoLookupPriority\)/g)||[]).length,3);
});
test('AUDIT08 06 tapping provisional unverified video does NOT start playback or another resolver',()=>{
 assert.match(play,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\) \{\s*val latest = results\.firstOrNull/);
 assert.match(play,/if \(!hasPublishableVideo\(latest\)\) \{/);
 assert.ok(play.indexOf('if (!hasPublishableVideo(latest))')<play.indexOf('playerConnection?.beginPlaybackPriorityBurst'));
 assert.match(retry,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\)[\s\S]*?return/);
});
test("AUDIT08 07 playback uses latest VERIFIED binding, never immutable poster's stale ID",()=>{
 assert.match(playback,/val admitted = publishedCoverSnapshots\.any \{ it\.fingerprint == selectedFingerprint \}/);
 assert.match(playback,/results\.firstOrNull \{[\s\S]*?hasPublishableVideo\(it\)/);
 assert.match(playback,/if \(isTurbo && mode == DiscogsDirectMode\.COVER && !hasPublishableVideo\(selectedSeed\)\) return/);
 assert.match(playback,/connection\.playNow\(selectedItem\)/);
});
test('AUDIT08 08 no premature cleanup if missing IDs: pending posters survive until exact T+10',()=>{
 assert.match(videoStage,/publishedCoverSnapshots\.any \{ poster ->/);
 assert.match(videoStage,/!hasPublishableVideo\(it\)/);
 assert.match(videoStage,/if \(remaining > 0L\) delay\(remaining\)/);
});
test('AUDIT08 09 source work does not resume after exit, video phase or post seal',()=>{
 assert.match(kotlin,/searchJob\?\.cancel\(\)/);
 assert.match(kotlin,/CloudMusicDiscovery\.cancelArchiveRequests\(\)/);
 assert.match(resume,/if \(isTurbo && mode == DiscogsDirectMode\.COVER\)[\s\S]*?return/);
 assert.match(videoStage,/session\.turboFinished = true/);
 assert.match(videoStage,/setCoverPerformanceLoad\(\s*active = false, heavy = false/);
});
test('AUDIT08 10 when 50 verified cards arrive early, cancel producer children but not playback',()=>{
 assert.match(turbo,/fun stopAtFullQuota\(\): Boolean/);
 assert.match(turbo,/providerJobs\.forEach \{ it\.cancel\(\) \}/);
 assert.match(turbo,/val providerJobs = mutableListOf<Job>\(\)/);
 assert.match(turbo,/val cloudSeeds = try \{/);
 assert.match(turbo,/if \(!quotaReached\) throw cancel/);
});
test('AUDIT08 11 exact source duplicates are suppressed; unrelated sources retain separate evidence',()=>{
 assert.match(turbo,/ConcurrentHashMap\.newKeySet<CoverSourceCandidate>\(\)/);
 assert.match(turbo,/val fresh = quick\.filter \{ sourceEvidence\.add\(it\) \}/);
 assert.match(turbo,/val fresh = items\.filter \{ sourceEvidence\.add\(it\) \}/);
 assert.match(turbo,/external\?\.candidates\.orEmpty\(\)\)\.distinct\(\)/);
});
test('AUDIT08 13 zero verified videos at the ten-second seal never displays the obsolete five-second loading message',()=>{
 assert.match(kotlin,/session\.turboFinished ->\s*"Nessuna cover con video verificato entro 10 secondi\."/);
 assert.match(kotlin,/loading \|\| !rankingFrozen ->\s*"Raccolgo le locandine in un unico blocco \(massimo 5 secondi\)/);
 assert.match(kotlin,/"Collego i video alle locandine \(massimo 10 secondi\)/);
 assert.match(kotlin,/if \(!isTurbo && nextBlockPool\.isNotEmpty\(\)\)/);
});
test("AUDIT08 12 keep player's source unchanged, preserve Home navigation",()=>{
 const blob=Buffer.concat([Buffer.from('blob '+player.length+'\0'),player]);
 assert.equal(createHash('sha1').update(blob).digest('hex'),'03138a7b0d6771e4c7dde0ecc9ab11a8046990ff');
 assert.equal((nav.match(/if \(!isSearchItem\) \{/g)||[]).length,2);
 assert.match(kotlin,/DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 64/);
});
