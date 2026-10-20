import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const src=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const header=src.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
const card=src.split('if (modernCoverCard) {')[1].split('        return\n    }')[0];
const menu=src.split('private fun MusicLabPlaybackBars(')[1].split('@Composable\nprivate fun DiscogsVersionCard(')[0];

test('LAB29 only touches UI in the existing native browser, never video resolver/queue/Cloud',()=>{
  assert.match(src,/fun resolveVideoChunk\(/);
  assert.match(src,/fun runSearch\(/);
  assert.match(src,/fun tryReplacement\(/);
  assert.match(src,/TURBO_VIDEO_COVERAGE_MAX_MS = 100_000L/);
  assert.match(src,/TURBO_VIDEO_REFILL_MAX_MS = 45_000L/);
});
test('compact header only shows artist, back action and category chips',()=>{
  assert.match(header,/navController\.popBackStack\(\)/);
  assert.match(header,/resolvedOriginalArtist\.ifBlank/);
  assert.match(header,/DirectCategorySelector\(/);
  assert.match(header,/musiclabArtistPoster/);
  assert.match(header,/Box\(\s*modifier = Modifier[\s\S]+height\(\(62f \* musiclabCoverExpandedProgress\)\.dp\)/);
});
test('sort sources and search share one revealable row above categories',()=>{
  const row=header.split('// Content retains its full layout')[1].split('// This row is *always* visible')[0];
  assert.match(row,/musiclabSortMenu/);
  assert.match(row,/musiclabSourcesMenu/);
  assert.match(row,/OutlinedTextField\(/);
  assert.match(row,/DirectVersionSort\.RELEVANCE to "Punteggio"/);
  assert.match(row,/DirectVersionSort\.OLDEST to "Più vecchi"/);
  assert.match(row,/DirectVersionSort\.NEWEST to "Più nuovi"/);
  assert.match(row,/showSourceDiagnostics = true/);
  assert.match(row,/if \(!loading\) runSearch\(\)/);
});
test('drag updates reveal for every pointer delta and only settles on release',()=>{
  assert.match(header,/detectVerticalDragGestures/);
  assert.match(header,/onVerticalDrag = \{ change, dragAmount ->/);
  assert.match(header,/coverHeaderDragProgress \+ dragAmount \/ musiclabCoverMotionPx/);
  assert.match(header,/coverHeaderDragging = true/);
  assert.match(header,/coverHeaderMotion\.snapTo\(release\)/);
  assert.match(header,/coverHeaderMotion\.animateTo\(/);
  assert.match(header,/dampingRatio = Spring\.DampingRatioNoBouncy/);
  assert.match(src,/if \(coverHeaderDragging\) coverHeaderDragProgress else coverHeaderMotion\.value/);
});
test('scrolling result list auto-collapses with 180ms animation',()=>{
  assert.match(src,/if \(movingForward\) \{[\s\S]+coverHeaderMotion\.animateTo\(0f, tween\(180\)\)/);
  assert.match(src,/session\.headerExpanded = false/);
});
test('no arrow toggle; artist back arrow remains exactly beside portrait',()=>{
  assert.match(header,/Text\(\s*"‹"/);
  assert.match(header,/shape = CircleShape/);
  assert.doesNotMatch(header,/headerExpanded = !headerExpanded/);
});
test('source menu preserves original diagnostics and archive action remains available',()=>{
  assert.match(header,/Text\("Archivio"\)/);
  assert.match(header,/MusicLabArchiveNavigationBridge\.ROUTE/);
  assert.match(header,/Text\("Dettagli delle fonti"\)/);
});
test('cover-only card has fixed position and compact poster 126dp',()=>{
  assert.match(src,/modernCoverCard = isTurbo/);
  assert.match(card,/Modifier\.size\(126\.dp\)/);
  // LAB30 approved theme-responsive fill supersedes LAB29 stroke.
  assert.doesNotMatch(card,/BorderStroke\(/);
  assert.match(card,/\.compositeOver\(MaterialTheme\.colorScheme\.surface\)/);
  assert.match(card,/Color\(0xFF22C871\)/);
  assert.match(card,/Color\(0xFFFA5757\)/);
  assert.match(card,/primaryDiscoverySource\(seed\)/);
  assert.match(card,/onDetails\(\)/);
  assert.match(card,/onRetryVideo\(\)/);
});
test('tap poster uses existing playback callback, no new play logic',()=>{
  assert.match(card,/clickable\(onClick = onPlay\)/);
  assert.match(src,/onPlay = \{ play\(seed\) \}/);
  assert.doesNotMatch(card,/playResolvedContext\(/);
});
test('bar animation lives inside selected poster, pauses visually when not playing',()=>{
  assert.match(card,/if \(selected\)/);
  assert.match(card,/MusicLabPlaybackBars\(/);
  assert.match(card,/animated = isPlaying/);
  assert.match(menu,/if \(animated\) level else 0\.45f/);
  assert.match(menu,/repeatMode = RepeatMode\.Reverse/);
  assert.match(src,/musiclabIsPlaying && seed\.fingerprint == selectedFingerprint/);
});
test('no section heading adds extra row to Turbo cover screen',()=>{
  assert.match(src,/if \(!isTurbo\) item\(key = "discogs_direct_original_versions_header"\)/);
  assert.match(src,/if \(!isTurbo\) item\(key = "discogs_direct_true_covers_header"\)/);
});
test('LAB29 signature and Android update-in-place family match',()=>{
  const env=fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab29-env.py','utf8');
  const workflow=fs.readFileSync('.github/workflows/turbo-lab29-cover-ui-gesture-only-apk.yml','utf8');
  assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1029"/);
  assert.match(env,/MusicLab Turbo LAB 29/);
  assert.match(env,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(workflow,/application-label:'MusicLab Turbo LAB 29'/);
  assert.match(workflow,/9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49/);
});
