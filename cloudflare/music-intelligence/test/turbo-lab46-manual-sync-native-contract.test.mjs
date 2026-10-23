import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// This contract validates the LAB46 native manual UI's authorisation boundary.
// Pure local file inspection only. No Android network calls, D1 writes,
// fake musical decisions, Worker deploys or device enrollment.
const root = 'app/src/main/kotlin/com/metrolist/music/';
const manual = readFileSync(root+'ui/component/Lab46ManualSyncActivity.kt','utf8');
const signer = readFileSync(root+'ui/component/Lab46StagingDeviceSigner.kt','utf8');
const transport = readFileSync(root+'ui/component/Lab46StagingSignedTransport.kt','utf8');
const settings = readFileSync(root+'ui/screens/settings/SettingsScreen.kt','utf8');
const manifest = readFileSync('app/src/main/AndroidManifest.xml','utf8');
const stamp = readFileSync('tools/universal-apk-builder/prepare-turbo-lab46-cloud-behavior-progressive-env.py','utf8');

test('exactly one native settings entry routes to non-exported Activity',()=>{
  assert.equal(settings.split('Sincronizzazione Cloud (LAB46)').length-1,1);
  assert.match(settings,/Intent\(context, Lab46ManualSyncActivity::class\.java\)/);
  assert.match(manifest,/<activity\s+android:name="\.ui\.component\.Lab46ManualSyncActivity"\s+android:exported="false"/);
  assert.equal((manifest.match(/<activity\s+android:name="\.ui\.component\.Lab46ManualSyncActivity"/g)||[]).length,1);
});
test('native UI cannot create Keystore keys or access Cloud on opening',()=>{
  assert.match(manual,/existingEnrollmentOrNull\(\)/);
  assert.match(manual,/pendingChoices\(applicationContext\)/);
  assert.doesNotMatch(manual,/\.enrollment\(\)|signSyncEnvelope\(|\.queue\(|startService\(|WorkManager/);
  assert.doesNotMatch(manual,/LAB46_WRITE_TOKEN|CLOUDFLARE_API_TOKEN|GEMINI_API_KEY/);
  const existing=signer.slice(signer.indexOf('internal suspend fun existingEnrollmentOrNull()'),signer.indexOf('suspend fun enrollment()'));
  assert.match(existing,/containsAlias\(ALIAS\)/);
  assert.doesNotMatch(existing,/pair\(\)|generateKeyPair\(/);
});
test('requires one selected existing revision and explicit Android confirmation',()=>{
  assert.match(manual,/selected:\s*CoverCloudPendingOutbox\.PendingChoice\?/);
  assert.match(manual,/setNegativeButton\("Annulla"/);
  const confirmation=manual.indexOf('setPositiveButton("Confermo una decisione")');
  const submit=manual.indexOf('Lab46StagingSignedTransport.sendOneSelectedPending(');
  assert.ok(confirmation>=0 && submit>confirmation);
  assert.match(manual,/sendButton\.isEnabled = false/);
  assert.match(manual,/REFUSED_OR_UNCERTAIN/);
  assert.match(manual,/LOCAL_ACK_FAILED/);
  assert.match(manual,/refreshChoices\(\)/);
});
test('exact existing staging origin, enrolled device, no new endpoint',()=>{
  assert.match(manual,/Lab46StagingSignedTransport.STAGING_ORIGIN/);
  assert.match(transport,/STAGING_ORIGIN = "https:\/\/musiclab-turbo-lab46-isolated-staging\.brunoverlezza\.workers\.dev"/);
  assert.match(transport,/existingEnrollmentOrNull\(\)/);
  assert.match(transport,/selectedPending\(context, choice\)/);
  assert.match(transport,/acknowledgeSelected\(context, choice, revision\)/);
  assert.match(transport,/receiptStep\(/);
  assert.doesNotMatch(manual,/http:\/\/|@Scheduled|Service\(/);
});
test('update over installed LAB46 is strictly newer, package remains protected',()=>{
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE=\\?"33048\\?"/);
  assert.match(stamp,/MUSICLAB_VERSION_CODE=\\?"33048\\?"/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
});
