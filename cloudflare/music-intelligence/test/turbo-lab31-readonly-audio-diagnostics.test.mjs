import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const service = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt','utf8');
const ui = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const playback = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt','utf8');
const native = fs.readFileSync('app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt','utf8');
const env = fs.readFileSync('tools/universal-apk-builder/prepare-turbo-lab31-env.py','utf8');
const wf = fs.readFileSync('.github/workflows/turbo-lab31-readonly-audio-diagnostics-apk.yml','utf8');

test('LAB31 measures only, no network calls in event logger',()=>{
 const logger=service.split('private fun lab31AudioEvent(')[1].split('    fun lab31BeginCoverTap()')[0];
 assert.match(logger,/java\.util\.ArrayDeque|lab31AudioEvents/);
 assert.match(logger,/synchronized\(lab31AudioLock\)/);
 assert.match(logger,/lab31AudioEvents\.size >= 48/);
 assert.doesNotMatch(logger,/runBlocking|Dispatchers|YouTube|Http|CloudMusic|player\.|MediaItem|launch/);
});

test('report remains local in memory and excludes video IDs and credentials',()=>{
 const report=service.split('fun lab31AudioDiagnosticReport()')[1].split('    private var ')[0];
 assert.match(report,/lab31AudioEvents\.forEach/);
 assert.match(report,/Non contiene ID video/);
 assert.doesNotMatch(report,/mediaId|resolvedVideoId|PlaybackData|token|Uri|url|currentMediaItem\?/);
 assert.match(service,/private val lab31AudioEvents = java\.util\.ArrayDeque<String>\(\)/);
});

test('tap event is taken before existing playback priority and playback engine untouched',()=>{
 assert.match(ui,/lab31BeginCoverTap\(\)[\s\S]*beginPlaybackPriorityBurst\("musiclab-version"\)/);
 assert.match(ui,/connection\.playNow\(selectedItem\)/);
 assert.match(service,/fun playNow\(item: MediaItem\)/);
 assert.match(service,/player\.addMediaItem\(targetIndex, item\)/);
 assert.match(service,/player\.seekTo\(targetIndex, 0L\)/);
});

test('stream resolve event attaches to existing request without new probes',()=>{
 assert.match(service,/InnerTubeXPlayer\.playerResponseForPlayback\(/);
 assert.match(service,/\.also \{ outcome ->\s*lab31AudioEvent\("STREAM_RESOLVE"/);
 assert.match(service,/success=" \+ outcome\.isSuccess/);
 assert.doesNotMatch(ui,/playerResponseForPlayback\(/);
});

test('buffering and first READY include measurable wall-clock timings',()=>{
 assert.match(service,/lab31AudioEvent\("BUFFERING"/);
 assert.match(service,/lab31AudioEvent\("READY"/);
 assert.match(service,/buffer_wait_ms=/);
 assert.match(service,/since_tap_ms=/);
 assert.match(service,/buffered_ms=/);
});

test('also differentiate local playback changes, focus changes and errors',()=>{
 assert.match(service,/override fun onIsPlayingChanged\(isPlaying: Boolean\)/);
 assert.match(service,/lab31AudioEvent\("PLAYING_FLAG"/);
 assert.match(service,/lab31AudioEvent\("PLAY_WHEN_READY"/);
 assert.match(service,/lab31AudioEvent\("AUDIO_FOCUS"/);
 assert.match(service,/lab31AudioEvent\("PLAYER_ERROR"/);
 assert.match(service,/error\.errorCodeName/);
});

test('report appears only behind existing artist overflow, not replacing approved layout',()=>{
 const header=ui.split('val musiclabCoverMotionPx =')[1].split('} else {\n        Surface(')[0];
 assert.match(header,/Text\("Diagnostica audio"\)/);
 assert.match(header,/Text\("Archivio"\)/);
 assert.match(header,/DirectCategorySelector\(/);
 assert.match(header,/detectVerticalDragGestures/);
 assert.match(ui,/Text\("Copia"\)/);
 assert.match(ui,/android\.content\.ClipboardManager/);
 assert.match(ui,/showAudioDiagnostic && isTurbo && mode == DiscogsDirectMode\.COVER/);
});

test('the identical LAB30 poster selection tint remains theme-responsive and borderless',()=>{
 const card=ui.split('if (modernCoverCard) {')[1].split('        return\n    }')[0];
 assert.match(card,/\.compositeOver\(MaterialTheme\.colorScheme\.surface\)/);
 assert.doesNotMatch(card,/BorderStroke\(/);
 assert.match(card,/MusicLabPlaybackBars/);
});

test('legacy version, signing and update-in-place are preserved',()=>{
 assert.match(env,/UAB_UPDATE_FAMILY_VERSION_CODE="1031"/);
 assert.match(env,/it\.verlezza\.musiclab\.turbo01/);
 assert.match(env,/MusicLab Turbo LAB 31/);
 assert.match(wf,/UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"/);
 assert.match(wf,/application-label:'MusicLab Turbo LAB 31'/);
 assert.match(native,/playerResponseForPlayback/);
 assert.match(playback,/service\.beginPlaybackPriorityBurst/);
});

test('the full regression tests include LAB24 through LAB31',()=>{
 const cmd=wf.split('run: node --test ')[1]?.split('\n')[0]||'';
 for (const n of [24,25,26,27,28,29,30,31]) assert.match(cmd,new RegExp('turbo-lab'+n+'-'));
});
