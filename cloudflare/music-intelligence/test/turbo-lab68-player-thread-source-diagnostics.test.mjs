import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const b=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const a=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt','utf8');
const m=JSON.parse(readFileSync('behaviors/musiclab-cover.json','utf8'));

test('Preserve user device verified LAB66 first thumbnail method',()=>{
  assert.equal(m.confirmedGoodBaseline.commit,'26f652fd34cbf6856d76c6efc8baf3fd14bf333f');
  assert.match(b,/LAB66 canonical Behavior Memory/);
  assert.match(b,/publishedCoverSnapshots = publishedCoverSnapshots \+ admitted/);
  assert.match(b,/YouTube\.search\(query\.text, YouTube\.SearchFilter\.FILTER_VIDEO\)/);
  assert.match(b,/Text\(if \(roundPlanComplete\) "Cerca ancora" else "Completa tornata"\)/);
});

test('ExoPlayer risk state not accessed from dispatcher IO provider callbacks',()=>{
  assert.match(b,/val documentaryFanOut = if \(playbackIsNormallyPlaying\(\)\) 1 else 2/);
  assert.match(b,/maxNetworkFanOut = documentaryFanOut/);
  const cb=b.slice(b.indexOf('onSourceCompleted = { candidates, diagnostic, _ ->'),b.indexOf('val cloud = if (includeDocumentary'));
  assert.match(cb,/val audioBusy = withContext\(Dispatchers\.Main\.immediate\)/);
  assert.match(cb,/backgroundWorkBlocked\(\)/);
  assert.match(cb,/isCoverAudioAtRisk\(\)/);
  const discogs=b.slice(b.indexOf('val discogs = if (includeDocumentary'), b.indexOf('val youtube = launch {', b.indexOf('val discogs = if (includeDocumentary')));
  assert.match(discogs,/val audioBusy = withContext\(Dispatchers\.Main\.immediate\)/);
  assert.match(discogs,/DiscogsVersionSource\.discoverCoverVersions\(/);
  assert.match(discogs,/withContext\(Dispatchers\.Main\.immediate\) \{\s*tryPublish\(release\.copy\(/);
});

test('The live source dialog never substitutes provisional LAB56 scores for Turbo results',()=>{
  assert.match(b,/LAB68: new Turbo results are already verified YouTube thumbnails/);
  assert.match(b,/if \(session\.turboFinished && activeCriteria != null\) return@LaunchedEffect/);
  assert.match(b,/recordSource\(diagnostic\)/);
  assert.match(b,/SourceDiagnosticsDialog\(\s*diagnostics = sourceDiagnostics,\s*turbo = isTurbo/);
  assert.match(b,/Fonti consultate nella tornata/);
  assert.match(b,/CoverSourceDiagnostic\("Discogs", false, 0,/);
});

test('First strict round requires channel evidence or documented work and late rounds remain permissive',()=>{
  assert.match(a,/val originalPerformer = originalArtist\.isNotBlank\(\) &&/);
  assert.match(a,/artistChannel\.contains\(originalArtist, ignoreCase = true\)/);
  assert.doesNotMatch(a,/videoTitle\.contains\(originalArtist, ignoreCase = true\)/);
  assert.match(a,/round == 3 -> weak/);
  assert.match(a,/else -> containsTitle \|\| exactTitle \|\| relation/);
});

test('No Cloud writer or native player source modified by LAB68',()=>{
  const patch=b.slice(b.indexOf('LAB66 canonical Behavior Memory'),b.indexOf('// A further Cover round never clears'));
  assert.doesNotMatch(patch,/DELETE FROM|DROP TABLE|CloudMusicDiscovery\.saveBrainDecision/);
});
