import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const browser = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const plan = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab66DirectYouTube.kt','utf8');
const coverInfo = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt','utf8');
const settings = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/CoverCompilationSettings.kt','utf8');
const archive = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabArchiveScreen.kt','utf8');
const cloud = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt','utf8');
const memory = JSON.parse(fs.readFileSync('behaviors/musiclab-cover.json','utf8'));

test('LAB66: video wall bypasses old 25+25 poster gate and archives on Turbo',()=>{
 assert.match(browser,/if \(isTurbo && mode == DiscogsDirectMode.COVER\) \{\s*runDirectYouTubeSearch\(advanceRound = appendRound\)/);
 assert.match(browser,/publishVerifiedThumbnail\(song\)/);
 assert.match(browser,/publishedCoverSnapshots = publishedCoverSnapshots \+ seed/);
 assert.match(browser,/session\.directYouTubeV2/);
 assert.match(browser,/\!isTurbo \|\| mode != DiscogsDirectMode.COVER \|\|\s*session\.directYouTubeV2/);
 assert.match(browser,/fun startDocumentaryRecovery\(\) \{\s*if \(session\.directYouTubeV2\) return/);
 assert.match(browser,/playbackVideoId = id/);
 assert.match(browser,/coverUrl = "https:\/\/i\.ytimg\.com\/vi\/\$id\/hqdefault\.jpg"/);
 assert.match(browser,/sourceUrl = "https:\/\/www\.youtube\.com\/watch\?v=\$id"/);
});
test('LAB66: one button orange complete round, green next round, fixed cards',()=>{
 assert.match(browser,/Text\(if \(directRoundExhausted\) "Cerca ancora" else "Completa tornata"\)/);
 assert.match(browser,/Color\(0xFF259C55\)/);
 assert.match(browser,/Color\(0xFFF29B27\)/);
 assert.match(plan,/const val QUERIES_PER_PRESS = 2/);
 assert.match(plan,/val families = when \(round\)/);
 assert.match(plan,/round >= 4/);
 assert.match(browser,/sameCrossSourceVersion\(old, seed\)/);
 assert.doesNotMatch(browser,/Text\("Cerca ancora · fino a 50 nuove cover"\)/);
});
test('LAB66: first round includes COVER.INFO linked foreign videos',()=>{
 assert.match(coverInfo,/suspend fun coverInfoDirectVideoHints\(/);
 assert.match(browser,/CoverDiscoverySources\.coverInfoDirectVideoHints\(/);
 assert.match(browser,/YouTube\.queue\(videoIds = listOf\(id\)\)/);
 assert.match(plan,/official international cover/);
});
test('LAB66: score UI percentage only and documented work grades independent of ID',()=>{
 assert.match(browser,/Precisione stimata:/);
 assert.doesNotMatch(browser,/Text\(\s*"Affidabilità: \$\{seed\.confidenceScore\}\/20"/);
 assert.doesNotMatch(browser,/"\$\{seed\.musiclabScore100\}\/100"/);
 assert.match(plan,/fun admissionSignals\(/);
 assert.match(plan,/fun estimatedPrecision\(/);
});
test('LAB66: cloud and archive source switches are separate from destructive reset',()=>{
 assert.match(settings,/MusicLabCoverArchiveEnabledKey/);
 assert.match(settings,/MusicLabCloudEnabledKey/);
 assert.match(archive,/if \(!archiveEnabled \|\| !cloudEnabled\)/);
 assert.match(browser,/val effectiveCloudEndpoint = if \(coverCloudEnabled\)/);
 assert.match(cloud,/internal val turboReadOnly: Boolean/);
 assert.match(cloud,/if \(turboReadOnly\) return@withContext false/);
});
test('LAB66: canonical Memory retirement and safety honored',()=>{
 const active = memory.rules.filter(x=>x.status==='active'), retired=memory.rules.filter(x=>x.status==='retired');
 assert.equal(memory.canonicalBehaviorRevision,'2026-10-26-video-thumb-progressive-manual-rounds-v2');
 for(const old of ['musiclab.cover.same-work','musiclab.cover.first-fifty','musiclab.cover.paged-rounds','musiclab.cover.category-rounds','musiclab.cover.score-separation']){
  assert.ok(retired.some(x=>x.id===old),old);
  assert.ok(active.some(x=>x.supersedes?.includes(old)),'replacement '+old);
 }
 assert.ok(active.some(x=>x.id==='musiclab.cover.youtube-thumbnail-and-link.v2'));
 assert.ok(active.some(x=>x.id==='musiclab.cover.no-replay-audio'));
});
