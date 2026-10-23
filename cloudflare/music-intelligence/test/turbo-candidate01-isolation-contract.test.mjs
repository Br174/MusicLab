import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=p=>readFileSync(p,'utf8');
const manifest=read('app/src/main/AndroidManifest.xml');
const transport=read('app/src/main/kotlin/com/metrolist/music/ui/component/Lab46StagingSignedTransport.kt');
const candidateStamp=read('tools/universal-apk-builder/prepare-turbo-candidate01-test-env.py');

test('CANDIDATA01 removes LAB46 staging enrollment route',()=>{
 const match=manifest.match(/<activity\s+android:name="\.ui\.component\.Lab46OwnerPairingActivity"[\s\S]*?<\/activity>/);
 assert.ok(match);
 assert.match(match[0],/android:enabled="false"/);
 assert.match(manifest,/android:name="\.ui\.component\.Lab46ManualSyncActivity"\s+android:exported="false"/);
});
test('candidate explicitly refuses live musical sends before signing or network',()=>{
 const method=transport.slice(transport.indexOf('internal suspend fun sendOneSelectedPending('));
 assert.ok(method.indexOf('BuildConfig.APPLICATION_ID')>=0);
 assert.ok(method.indexOf('"it.verlezza.musiclab.candidate01"')>=0);
 assert.ok(method.indexOf('return@withContext Result.NOT_PAIRED')>=0);
 assert.ok(method.indexOf('return@withContext Result.NOT_PAIRED') < method.indexOf('signSyncEnvelope(body)'));
});
test('new package and test signature are distinct, original LAB signing stays unused',()=>{
 assert.match(candidateStamp,/it\.verlezza\.musiclab\.candidate01/);
 assert.match(candidateStamp,/MusicLab-Turbo-CANDIDATA01/);
 assert.match(candidateStamp,/GENERATED_EPHEMERAL_AT_CI/);
 assert.match(candidateStamp,/musiclab-candidate01-build-only\.jks\.b64/);
 assert.match(candidateStamp,/10001/);
 assert.match(candidateStamp,/source\/UAB guard/);
});
test('retain first-50 source, no audio/search alteration in candidate gate',()=>{
 const workflow=read('.github/workflows/musiclab-turbo-candidate01-test-apk.yml');
 assert.match(workflow,/MusicService\.kt/);
 assert.match(workflow,/CoverSameWorkGate\.kt/);
 assert.match(workflow,/DiscogsDirectVersionBrowser\.kt/);
 assert.match(workflow,/CRASH_REPORT_TOKEN: ''/);
 assert.doesNotMatch(workflow,/wrangler deploy|database execute.*remote|CLOUDFLARE_API_TOKEN/);
});
