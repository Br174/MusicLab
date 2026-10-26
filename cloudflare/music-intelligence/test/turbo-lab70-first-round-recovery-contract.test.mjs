import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const b=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const a=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt','utf8');
const memory=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));
const start=b.indexOf('// LAB66 canonical Behavior Memory');
const end=b.indexOf('// A further Cover round never clears',start);
const active=b.slice(start,end);
assert.ok(start>0 && end>start, 'LAB66 active publisher still exists');

test('Preserve the original device-tested LAB66 publisher and identity',()=>{
  assert.equal(memory.confirmedGoodBaseline.commit,'26f652fd34cbf6856d76c6efc8baf3fd14bf333f');
  assert.match(active,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
  assert.match(active,/session\.playReadyVideoIds\.add\(id\)/);
  assert.match(active,/CoverLab71FoundationGate\.isDuplicate\(seed, priorEntries\)/);
  assert.match(readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGate.kt','utf8'),/CoverLabTurboTwinGuard\.sameRecording\(previous, seed\)/);
  assert.match(active,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
});
test('Felicità: first original-artist query consults credited YouTube Music songs',()=>{
  assert.match(active,/val musicSongs = if \(thisRound == 1 && offset == 0 &&/);
  assert.match(active,/YouTube\.SearchFilter\.FILTER_SONG/);
  assert.match(active,/YouTubeWebSearch\.search\(query\.text\)/);
  assert.match(active,/val providerVideos = \(musicSongs \+ found\.orEmpty\(\) \+ webSongs\)/);
  assert.match(active,/tryPublish\(candidate, searchedVideo = video\)/);
});
test('Source-backed video metadata reused, documentary-only IDs still independently checked',()=>{
  assert.match(active,/searchedVideo\?\.takeIf \{ searched ->/);
  assert.match(active,/searched\.id == id && searched\.title\.isNotBlank\(\)/);
  assert.match(active,/searched\.artists\.any \{ it\.name\.isNotBlank\(\) \}/);
  assert.match(active,/YouTube\.queue\(videoIds = listOf\(id\)\)/);
  assert.match(active,/for \(attempt in 0\.\.1\)/);
  assert.match(active,/metadata\.id != id \|\| metadata\.title\.isBlank\(\)/);
  assert.match(active,/earlyVideoIds\.remove\(id\)/);
});
test('Empty first presses never consume unknown later documentary or official queries',()=>{
  assert.match(active,/session\.youtubeQueryCursor = cursor \+ offset \+ 1/);
  assert.match(active,/session\.youtubeQueryCursor >= queries\.size/);
  assert.doesNotMatch(active,/session\.roundEmptyPasses >= 2/);
  assert.match(active,/if \(found == null && musicSongs\.isEmpty\(\) && webSongs\.isEmpty\(\)\)/);
  assert.match(active,/session\.roundQueryRetries\+\+/);
});
test('First-round same-title artist gate, player safety and Cloud isolation intact',()=>{
  assert.match(a,/val originalPerformer = originalArtist\.isNotBlank\(\) &&/);
  assert.doesNotMatch(a,/videoTitle\.contains\(originalArtist, ignoreCase = true\)/);
  assert.match(a,/round <= 1 -> strong/);
  assert.match(active,/backgroundWorkBlocked\(\)/);
  assert.match(active,/isCoverAudioAtRisk\(\)/);
  assert.doesNotMatch(active,/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
});
