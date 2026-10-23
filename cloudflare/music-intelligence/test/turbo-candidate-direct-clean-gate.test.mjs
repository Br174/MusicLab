import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
const base='app/src/main/kotlin/com/metrolist/music/ui/component/';
const manifest=readFileSync('app/src/main/AndroidManifest.xml','utf8');
const manual=readFileSync(base+'Lab46ManualSyncActivity.kt','utf8');
const transport=readFileSync(base+'Lab46StagingSignedTransport.kt','utf8');
const settings=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt','utf8');
const stamp=readFileSync('tools/universal-apk-builder/prepare-turbo-lab46-cloud-behavior-progressive-env.py','utf8');

test('old technical pairing Activity and deep link are removed, ordinary app retained',()=>{
  assert.equal(existsSync(base+'Lab46OwnerPairingActivity.kt'),false);
  assert.doesNotMatch(manifest,/musiclabturbo|Lab46OwnerPairingActivity|lab46\/pair/);
  assert.match(manifest,/android:name="\.ui\.component\.Lab46ManualSyncActivity"\s+android:exported="false"/);
});
test('manual user action and selected single revision are still required',()=>{
  assert.match(settings,/Sincronizzazione Cloud \(LAB46\)/);
  assert.match(manual,/setPositiveButton\("Confermo una decisione"/);
  assert.match(manual,/sendOneSelectedPending\(/);
  assert.match(manual,/existingEnrollmentOrNull\(\)/);
  assert.match(manual,/Aggi?orna decisioni locali|Aggiorna decisioni locali/);
  assert.match(transport,/receiptStep\(/);
  assert.match(transport,/acknowledgeSelected\(/);
});
test('Cloud staging remains explicitly staging and not accidentally promoted to production',()=>{
  assert.match(transport,/STAGING_ORIGIN = "https:\/\/musiclab-turbo-lab46-isolated-staging\.brunoverlezza\.workers\.dev"/);
  assert.doesNotMatch(manual,/startService\(|WorkManager|auto.?sync/);
});
test('candidate is SAME exact Android family with sequential update code and original signing profile',()=>{
  assert.match(stamp,/UAB_UPDATE_FAMILY_VERSION_CODE=\\"33048\\"/);
  assert.match(stamp,/MUSICLAB_VERSION_CODE=\\"33048\\"/);
  assert.match(stamp,/it\.verlezza\.musiclab\.turbo01/);
  assert.match(stamp,/9A:2F:67:CF:B3:C1:99:83/);
});
test('source exact freeze and test-only pairing removal are gated by protected workflow',()=>{
  const w=readFileSync('.github/workflows/musiclab-turbo-lab46-direct-candidate-clean.yml','utf8');
  assert.match(w,/SOURCE=dbfed95eae0a40a13e4c103031ea75481054a638/);
  assert.match(w,/MusicService\.kt/);
  assert.match(w,/CoverSameWorkGate\.kt/);
  assert.match(w,/SettingsScreen\.kt/);
  assert.match(w,/Lab46ManualSyncActivity\.kt/);
  assert.match(w,/CANDIDATE_PROVENANCE/);
  assert.doesNotMatch(w,/CLOUDFLARE_API_TOKEN|wrangler deploy/);
});
