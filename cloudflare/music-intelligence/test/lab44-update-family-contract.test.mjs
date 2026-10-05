import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const env = fs.readFileSync('uab-project.env', 'utf8');
const builder = fs.readFileSync('tools/universal-apk-builder/build-apk.sh', 'utf8');
const localCore = fs.readFileSync('.motorlab/MOTORLAB_LOCAL_CORE.txt', 'utf8');
const sync = fs.readFileSync('.motorlab/MOTORLAB_SYNC_STATE.txt', 'utf8');
const hook = fs.readFileSync('MOTORLAB_PROJECT_HOOK.txt', 'utf8');

test('LAB Family 01 has a fixed Android identity', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_ID="01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env, /export METROLIST_APPLICATION_ID="it\.verlezza\.musiclab\.labupdate01"/);
  assert.match(env, /UAB_PARALLEL_INSTALL="off"/);
  assert.doesNotMatch(env, /UAB_PARALLEL_INSTALL="on"/);
});

test('first family build is Bruno-facing LAB 44 aggiornamento with monotonic version code', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_VISIBLE_NAME="LAB 44 aggiornamento"/);
  assert.match(env, /export METROLIST_APP_NAME="MusicLab LAB 44 aggiornamento"/);
  assert.match(env, /export MUSICLAB_VERSION_CODE="4401"/);
  assert.match(env, /UAB_OUTPUT_NAME="MusicLab-LAB44-aggiornamento"/);
});

test('family uses one stable test-only signing profile', () => {
  assert.match(env, /UAB_UPDATE_FAMILY_SIGNING_PROFILE="musiclab-lab-family-01-test"/);
  assert.match(env, /UAB_FAMILY_SIGNING_KEY_B64_FILE="\.uab\/signing\/musiclab-lab-family-01\.jks\.b64"/);
  assert.match(env, /UAB_FAMILY_KEY_ALIAS="musiclab_lab_family_01"/);
  assert.match(env, /UAB_UPDATE_FAMILY_CERT_SHA256="9A:2F:67:CF:B3:C1:99:83:68:13:AD:DB:F7:BD:FB:0F:A6:5E:DC:76:5F:FA:CE:4B:6D:F9:49:0B:B0:96:27:49"/);
  assert.match(builder, /setup_family_signing\(\)/);
  assert.match(builder, /METROLIST_DEBUG_KEYSTORE_PATH/);
  assert.match(builder, /UAB_FAMILY_SIGNING_KEY_B64_FILE/);
});

test('MotorLab r18 update-family and paired safety lifecycle are active in MusicLab satellite', () => {
  assert.match(localCore, /RELEASE=2026\.10\.05-r18/);
  assert.match(localCore, /UPDATE_FAMILY_LIFECYCLE=required/);
  assert.match(sync, /LOCAL_RELEASE=2026\.10\.05-r18/);
  assert.match(sync, /UPDATE_FAMILY_MODE=lab/);
  assert.match(sync, /UPDATE_FAMILY_ID=01/);
  assert.match(sync, /PAIRED_SAFETY_LAB=required/);
  assert.match(hook, /MOTORLAB_UPDATE_FAMILY_LIFECYCLE_MODULE=MOTORLAB_UPDATE_FAMILY_LIFECYCLE_V1\.txt/);
  assert.match(hook, /MOTORLAB_PAIRED_SAFETY_LAB=required/);
});

test('paired LAB di sicurezza 44 is built from same source with different family and certificate', () => {
  const workflow = fs.readFileSync('.github/workflows/lab44-aggiornamento-family-01-gate.yml', 'utf8');
  assert.match(workflow, /MusicLab-LAB-di-sicurezza-44/);
  assert.match(workflow, /it\.verlezza\.musiclab\.safety44/);
  assert.match(workflow, /musiclab_safety_44/);
  assert.match(workflow, /apksigner/);
  assert.match(workflow, /PAIRED_UPDATE_SHA=\$GITHUB_SHA/);
  assert.match(workflow, /Upload LAB di sicurezza 44/);
});
