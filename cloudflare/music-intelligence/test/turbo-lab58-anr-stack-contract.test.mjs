import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root='app/src/main/kotlin/com/metrolist/music/';
const app=readFileSync(root+'App.kt','utf8');
const screen=readFileSync(root+'ui/component/DiscogsDirectVersionBrowser.kt','utf8');
const watchdog=readFileSync(root+'utils/AnrWatchdog.kt','utf8');
const reentry=screen.split('LaunchedEffect(sessionKey, discogsToken, restorationReady)')[1]
 .split('// Fully verified sessions remain cache-only')[0];

test('LAB58: real main-thread stack survives ANR even if GitHub reporting is disabled',()=>{
 assert.match(app,/AnrWatchdog\.start\(this\)/);
 assert.match(watchdog,/appContext\?\.getSharedPreferences\(PREF, Context\.MODE_PRIVATE\)/);
 assert.match(watchdog,/mainStack\.take\(22\)/);
 assert.match(watchdog,/putString\(KEY_REPORT, stackReport\)/);
 assert.match(watchdog,/fun latestReport\(context: Context\): String/);
 assert.match(watchdog,/CrashReporter\.reportAnr\(mainStack, ANR_THRESHOLD_MS\)/);
});
test('LAB58: watchdog is not blocked behind priority-starved user-interface work',()=>{
 assert.match(watchdog,/priority = Thread\.NORM_PRIORITY/);
 assert.match(watchdog,/name = "Meld-ANR-Watchdog"/);
});
test('LAB58: no every-poster SharedPreferences/video scan or full provider search on reentry',()=>{
 assert.match(reentry,/if \(!restorationReady\) return@LaunchedEffect/);
 assert.match(reentry,/if \(!session\.initialized && initialTitle\.isNotBlank\(\)\)/);
 assert.doesNotMatch(reentry,/hasPublishableVideo\(/);
 assert.doesNotMatch(reentry,/turboLastAutoRepairAtMs/);
});
test('LAB58: Copyable Android ANR diagnostic in the existing Cover header menu',()=>{
 assert.match(screen,/Text\("Diagnostica blocchi"\)/);
 assert.match(screen,/AnrWatchdog\.latestReport\(context\)/);
 assert.match(screen,/val report = com\.metrolist\.music\.utils\.AnrWatchdog/);
 assert.match(screen,/newPlainText\("MusicLab ANR stack", anrDiagnosticText\)/);
 assert.match(screen,/scope\.launch\(Dispatchers\.IO\)/);
});
test('LAB58: provisional poster rendering skips expensive video preferences reads',()=>{
 const scaler=screen.split('fun videoCalibratedDisplaySeed(seed: DiscogsVersionSeed): DiscogsVersionSeed {')[1]
    .split('fun hasTurboStreamReady(')[0];
 assert.match(scaler,/CoverLab55VisibleCandidates\.isProvisional\(/);
 assert.match(scaler,/\) return seed/);
 assert.match(scaler,/val raw = seed\.confidenceScore/);
});

test('LAB58: source-backed LAB55 candidate publication and LAB57 async restoration preserved',()=>{
 assert.match(screen,/val initialCovers = CoverLab55VisibleCandidates\.publish/);
 assert.match(screen,/publishedCoverSnapshots = CoverLab55VisibleCandidates\.publish/);
 assert.match(screen,/CoverRoundStorage\.load\(context, sessionKey\)/);
 assert.match(screen,/val saved = withContext\(Dispatchers\.IO\)/);
 assert.match(screen,/Da verificare · registrazione candidata/);
});
