/**
 * LAB46 isolated Cloud staging activation package: config-only verifications.
 * Nothing here performs an external request, secret mutation or deployment.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root='cloudflare/music-intelligence/';
const read=path=>readFileSync(root+path,'utf8');
const inactive=JSON.parse(read('lab46-staging-only/wrangler.staging.verified.jsonc'));
const activation=JSON.parse(read('lab46-staging-only/wrangler.staging.https-activation.jsonc'));
const production=JSON.parse(read('wrangler.jsonc'));
const ui=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/Lab46OwnerPairingActivity.kt','utf8');
const manifest=readFileSync('app/src/main/AndroidManifest.xml','utf8');
const signer=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/Lab46StagingDeviceSigner.kt','utf8');
const transport=readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/Lab46StagingSignedTransport.kt','utf8');

test('approved HTTPS activation file differs ONLY in public workers.dev route plus no preview URLs',()=>{
  const expected=structuredClone(inactive);
  expected.workers_dev=true;
  expected.preview_urls=false;
  assert.deepEqual(activation,expected);
  assert.equal(inactive.workers_dev,false);
  assert.equal(activation.workers_dev,true);
  assert.equal(activation.preview_urls,false);
  for (const candidate of [inactive,activation]){
    assert.equal(candidate.name,'musiclab-turbo-lab46-isolated-staging');
    assert.equal(candidate.main,'worker-entry.mjs');
    assert.equal(candidate.d1_databases?.length,1);
    assert.equal(candidate.d1_databases[0].binding,'DB');
    assert.equal(candidate.d1_databases[0].database_name,'musiclab-turbo-lab46-staging');
    assert.equal(candidate.d1_databases[0].database_id,
      'e66bd500-cdaa-453c-a81c-c624a9d2611e');
    assert.equal(candidate.vars?.LAB46_ISOLATED_STAGING,'1');
    assert.equal(candidate.vars?.LAB46_ISOLATED_DB_NAME,'musiclab-turbo-lab46-staging');
    assert.ok(!candidate.routes && !candidate.route && !candidate.triggers);
    assert.deepEqual(candidate.secrets?.required,
      ['LAB46_WRITE_TOKEN','LAB46_DEVICE_PUBLIC_SPKI']);
    assert.ok(!('LAB46_WRITE_TOKEN' in candidate.vars));
    assert.ok(!('LAB46_DEVICE_PUBLIC_SPKI' in candidate.vars));
    assert.ok(!('CLOUDFLARE_API_TOKEN' in candidate.vars));
    assert.ok(!('GEMINI_API_KEY' in candidate.vars));
    assert.notEqual(candidate.name,production.name);
    assert.notEqual(candidate.d1_databases[0].database_id,production.d1_databases[0].database_id);
  }
});
test('staging pairing link is exact and separate, existing app UI never auto-pairs',()=>{
  assert.equal((manifest.match(/Lab46OwnerPairingActivity/g)||[]).length,1);
  assert.match(manifest,/android:scheme="musiclabturbo" android:host="lab46" android:path="\/pair"/);
  assert.match(ui,/intent\?\.data\?\.toString\(\) != "musiclabturbo:\/\/lab46\/pair"/);
  assert.match(ui,/Lab46StagingDeviceSigner\.enrollment\(\)/);
  assert.match(ui,/Intent\.ACTION_SEND/);
  assert.match(ui,/Genera \/ recupera chiave pubblica sul telefono/);
  for(const source of [ui,transport]){
    assert.doesNotMatch(source,/Bearer\s|LAB46_WRITE_TOKEN|CLOUDFLARE_API_TOKEN|GEMINI_API_KEY/);
  }
  assert.doesNotMatch(ui,/signSyncEnvelope\(|sendOnePending\(|OkHttpClient|startService\(/);
  assert.match(signer,/AndroidKeyStore/);
  assert.match(signer,/setUserAuthenticationRequired\(false\)/);
});
test('signed sync remains dormant without explicit binding and receipt',()=>{
  assert.match(transport,/if \(!binding\.ownerConfirmed\)/);
  assert.match(transport,/binding\.confirmedDeviceKeyId/);
  assert.match(transport,/safeOrigin\(binding\.explicitlyApprovedOrigin\)/);
  assert.match(transport,/followRedirects\(false\)/);
  assert.match(transport,/followSslRedirects\(false\)/);
  assert.match(transport,/verifiedReceipt\(result, revision, fingerprint\)/);
  assert.match(transport,/CoverCloudPendingOutbox\.acknowledge/);
});
test('production path and approved native graphics are absent from staging files',()=>{
  const ingress=read('lab46-staging-only/worker-entry.mjs');
  assert.match(ingress,/stagingCloudWriteGate/);
  assert.match(ingress,/verifyDeviceSignature/);
  assert.doesNotMatch(ingress,/api\.cloudflare\.com|wrangler deploy|execSync/);
  assert.notEqual(production.name,activation.name);
  assert.equal(production.d1_databases[0].database_id,
    '7f36c6a5-5361-47cf-8520-b2ba398ea731');
  assert.doesNotMatch(ui,/MusicService|InnerTubeXPlayer|AppNavigation|CoverSameWorkGate/);
});

test('one-shot staging activation REQUIRES owner-created existing Worker, never product Admin',()=>{
  const installer=read('lab46-staging-only/activation_one_shot.sh');
  const workflow=readFileSync('.github/workflows/lab46-staging-one-shot-deploy-on-owner-approval.yml','utf8');
  assert.match(installer,/permit_update_existing_staging_worker:true/);
  assert.doesNotMatch(installer,/permit_one_new_staging_worker:true/);
  assert.match(installer,/STAGING_WORKER_NOT_PROVEN_EXISTING/);
  assert.match(installer,/\[\[ "\$http" == 200 \]\]/);
  assert.match(installer,/STAGING_WORKER_NOT_PROVEN_EXISTING_\$http/);
  assert.match(installer,/git diff --name-only HEAD\^ HEAD/);
  assert.match(installer,/SOURCE_SHA_CHANGED_AFTER_APPROVAL/);
  assert.match(installer,/PUBLIC_SPKI_FINGERPRINT_NOT_CONFIRMED/);
  assert.match(installer,/const spki=approval\.device_public_spki\|\|'';/);
  assert.match(installer,/EXTRA_APPROVAL_FIELDS_OR_POSSIBLE_SECRET_REJECTED/);
  assert.match(installer,/LAB46_DEVICE_PUBLIC_SPKI=\$\(node -e/);
  assert.doesNotMatch(workflow,/vars\.MUSICLAB_LAB46_DEVICE_PUBLIC_SPKI/);
  assert.match(installer,/staging_schema_postmigration_readonly\.mjs/);
  assert.match(installer,/ROLLBACK_NEEDED=yes/);
  assert.match(workflow,/activation-trigger-approved\.json/);
  assert.doesNotMatch(workflow,/workflow_dispatch|schedule:|pull_request:/);
  assert.doesNotMatch(workflow,/MUSICLAB_LAB46_CF_PRODUCTION/);
});


test('one-shot scripts always configure secrets while workers.dev is OFF and only later expose HTTPS',()=>{
  const script=read('lab46-staging-only/activation_one_shot.sh');
  assert.match(script,/PRIVATE_BOOTSTRAP_CFG=/);
  assert.match(script,/delete base\.secrets/);
  assert.match(script,/writeFileSync\(path,JSON\.stringify\(base,null,2\)/);
  const privateDeploy=script.indexOf('npx --yes wrangler@4 deploy --config "$PRIVATE_BOOTSTRAP_CFG"');
  const firstSecret=script.indexOf('npx --yes wrangler@4 secret put LAB46_WRITE_TOKEN');
  const secondSecret=script.indexOf('npx --yes wrangler@4 secret put LAB46_DEVICE_PUBLIC_SPKI');
  const publicDeploy=script.indexOf('npx --yes wrangler@4 deploy --config lab46-staging-only/wrangler.staging.https-activation.jsonc',firstSecret);
  assert.ok(privateDeploy>=0 && firstSecret>privateDeploy && secondSecret>firstSecret &&
    publicDeploy>secondSecret,'all Worker writes must start private, secrets before public');
  const secretsSection=script.slice(firstSecret,publicDeploy);
  assert.match(secretsSection,/--config "\$PRIVATE_BOOTSTRAP_CFG"/);
  assert.doesNotMatch(secretsSection,/secret put [A-Z_]+.*--config lab46-staging-only\/wrangler\.staging\.verified\.jsonc/);
  assert.match(script,/ROLLBACK_NEEDED=yes/);
  assert.match(script,/rm -f -- "\$PRIVATE_BOOTSTRAP_CFG"/);
  assert.match(script,/STAGING_WORKER_NOT_PROVEN_EXISTING/);
  assert.match(script,/PUBLIC_SPKI_FINGERPRINT_NOT_CONFIRMED/);
});
