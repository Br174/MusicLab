import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root='app/src/main/kotlin/com/metrolist/music/ui/component/';
const b=readFileSync(root+'DiscogsDirectVersionBrowser.kt','utf8');
const a=readFileSync(root+'CoverLabTurboRoundAdmission.kt','utf8');
const p=readFileSync(root+'CoverLab72PublicationProof.kt','utf8');
const sources=readFileSync(root+'CoverDiscoverySources.kt','utf8');
const queries=readFileSync(root+'CoverRoundSearchPlan.kt','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const active=b.slice(b.indexOf('// LAB66 canonical Behavior Memory'),b.indexOf('// A further Cover round never clears'));

test('Permanent user-approved discographic-first rules supersede previous tiers',()=>{
 assert.equal(memory.canonicalBehaviorRevision,'2026-10-27-lab72-discographic-first-cover-info-priority-v4');
 assert.equal(memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v3').status,'retired');
 const v4=memory.rules.find(x=>x.id==='musiclab.cover.source-and-category-rounds.v4');
 assert.equal(v4.status,'active');
 assert.ok(v4.protected);
 assert.match(v4.behavior,/COVER.INFO foreign-language Work adaptations EARLY/);
 assert.equal(new Set(memory.rules.map(r=>r.id)).size,memory.rules.length);
 for(const conflict of memory.conflictDispositions){
  assert.equal(memory.rules.find(r=>r.id===conflict.oldRule).status,'retired',conflict.oldRule);
  assert.equal(memory.rules.find(r=>r.id===conflict.replacementRule).status,'active',conflict.replacementRule);
 }
});

test('Only actual track/release catalog IDs count, never YouTube or fake album',()=>{
 assert.match(sources,/releaseVerified = item\.optLong\("trackId"\) > 0L &&/);
 assert.match(sources,/item\.optLong\("collectionId"\) > 0L/);
 assert.match(sources,/releaseVerified = !candidate\.spotifyTrackId\.isNullOrBlank\(\)/);
 assert.match(sources,/releaseEvidence = candidate\.spotifyTrackId/);
 assert.match(p,/candidate\.releaseVerified/);
 assert.match(p,/candidate\.album\.isNullOrBlank\(\)/);
 assert.match(p,/candidate\.releaseEvidence\.isNullOrBlank\(\)/);
 assert.doesNotMatch(p,/officialMarker \|\| credibleOfficialChannel/);
});

test('Verified release and Work composition are independently matched',()=>{
 assert.match(p,/TitleMeaningResolver\.sameArtist\(candidate\.artist, proof\.artist\)/);
 assert.match(p,/sameRecordingCategory\(proof, candidate\)/);
 assert.match(p,/if \(videoLive && !proofLive\) return false/);
 assert.match(p,/if \(videoRemix && !proofRemix\) return false/);
 assert.match(p,/documented\.workRelationConfirmed/);
 assert.match(p,/it\.equals\("COVER.INFO", true\)/);
 assert.match(p,/workRelationConfirmed = candidate\.workRelationConfirmed \|\|/);
});

test('First-round source videos are staged until evidence and ranked COVER.INFO first',()=>{
 assert.match(active,/val firstRoundVideos = mutableListOf<Pair<CoverSourceCandidate, SongItem\?>>\(\)/);
 assert.match(active,/if \(thisRound == 1 && !flushFirstRound\)/);
 assert.match(active,/discographyProofs \+= candidates\.filter\(/);
 assert.match(active,/CoverLab72PublicationProof\.withDocumentedRelease\(/);
 assert.match(active,/CoverLab72PublicationProof\.isReleaseProof\(publishedCandidate\)/);
 assert.match(active,/CoverLab72PublicationProof\.firstRoundPriority\(/);
 assert.match(p,/coverInfo -> 0/);
 assert.match(p,/TitleMeaningResolver\.sameArtist\(candidate\.artist, originalArtist\) -> 1/);
 assert.match(active,/tryPublish\(verified, searchedVideo = video, flushFirstRound = true\)/);
 assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
});

test('Discogs now really verifies many other singers via release tracklists',()=>{
 const discogs=active.slice(active.indexOf('// LAB72: Discogs is a verified RELEASE'),active.indexOf('val youtube = launch {',active.indexOf('// LAB72: Discogs is a verified RELEASE')));
 assert.match(discogs,/DiscogsVersionSource\.discoverCoverVersions\(/);
 assert.match(discogs,/maxDetails = 16/);
 assert.match(discogs,/it\.releaseId > 0 && it\.track != null/);
 assert.match(discogs,/releaseEvidence = record\.discogsUrl/);
 assert.match(discogs,/discographyProofs \+= proofRows/);
 assert.doesNotMatch(discogs,/TitleMeaningResolver\.sameArtist\(\s*record\.artist, originalArtist/);
 assert.doesNotMatch(discogs,/loadVersionPage\(/);
});

test('Round two is professional TV/recorded live, not amateur YouTube',()=>{
 assert.match(active,/CoverLab72PublicationProof\.professionalUnreleased\(publishedCandidate\)/);
 assert.match(p,/sanremo/);
 assert.match(p,/x\[\\s-\]\*factor/);
 assert.match(p,/the\[\\s-\]\*voice/);
 assert.match(queries,/Festival di Sanremo cover live/);
 assert.match(queries,/Rai TV performance cover/);
 assert.match(queries,/X Factor cover live/);
 assert.match(queries,/The Voice cover live/);
 assert.match(queries,/bedroom cover/);
 assert.doesNotMatch(queries,/karaoke singer cover/);
});

test('LAB71 safety gates, immutable title, no karaoke and playback governor remain',()=>{
 assert.match(a,/CoverLab71FoundationGate\.matchesRequestedWork/);
 assert.match(active,/CoverLab71FoundationGate\.isDuplicate\(seed, priorEntries\)/);
 assert.match(active,/CoverLab71FoundationGate\.withinOriginalCaps\(/);
 assert.match(active,/backgroundWorkBlocked\(\)/);
 assert.match(active,/isCoverAudioAtRisk\(\)/);
 assert.doesNotMatch(active,/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
 assert.match(active,/i\.ytimg\.com\/vi\/\$\{video\.id\}\/hqdefault\.jpg/);
});
