/**
 * LAB46 isolated Worker/D1 binding readiness audit.
 * Purely local: no Cloudflare requests, no credentials, no deploy, no SQL.
 * A passing preparation audit is NOT permission to deploy.
 */
import assert from 'node:assert/strict';
import { readFileSync, appendFileSync } from 'node:fs';

const VERIFIED_ACCOUNT_ID = '5781b597517f6f7fe25751170a99b9ab';
const VERIFIED_STAGING_D1_ID = 'e66bd500-cdaa-453c-a81c-c624a9d2611e';
const PINNED_PRODUCTION_D1_ID = '7f36c6a5-5361-47cf-8520-b2ba398ea731';
const STAGING_NAME = 'musiclab-turbo-lab46-staging';
const WORKER_NAME = 'musiclab-turbo-lab46-isolated-staging';
const ROOT = 'cloudflare/music-intelligence/';
const read = path => readFileSync(ROOT + path, 'utf8');

const stageTemplate = JSON.parse(read('wrangler.lab46-staging.template.jsonc'));
const production = JSON.parse(read('wrangler.jsonc'));
const bootstrap = read('lab46-staging-only/bootstrap.sql');
const authCode = read('src/lab46-staging-write-guard.js');
const brainCode = read('src/worker-v20.js');
const runtimeCode = read('src/worker-v20-runtime.js');

function validate(stage,prod,bootstrapSql,guardCode,workerCode,runtime) {
  assert.equal(VERIFIED_ACCOUNT_ID.length,32);
  assert.equal(stage.name,WORKER_NAME,'staging Worker name mismatch');
  assert.equal(stage.main,'src/worker-v20-runtime.js','unexpected Worker entrypoint');
  assert.equal(stage.vars?.LAB46_ISOLATED_STAGING,'1','staging mode must be enabled');
  assert.equal(stage.vars?.LAB46_ISOLATED_DB_NAME,STAGING_NAME);
  assert.equal(stage.d1_databases?.length,1,'one isolated binding expected');
  const binding=stage.d1_databases[0];
  assert.equal(binding.binding,'DB','binding name must match Worker');
  assert.equal(binding.database_name,STAGING_NAME);
  assert.equal(binding.database_id,'REPLACE_WITH_REAL_ISOLATED_STAGING_D1_ID',
    'tracked staging template must remain inert');
  assert.equal(prod.d1_databases?.length,1);
  const productionDb=prod.d1_databases[0];
  assert.equal(productionDb.database_id,PINNED_PRODUCTION_D1_ID);
  assert.notEqual(productionDb.database_id,VERIFIED_STAGING_D1_ID);
  assert.notEqual(productionDb.database_name,STAGING_NAME);
  assert.notEqual(prod.name,WORKER_NAME);
  assert.ok(bootstrapSql.includes('lab46_staging_sentinel'));
  assert.ok(bootstrapSql.includes('lab46_sync_receipts'));
  assert.ok(bootstrapSql.includes('musiclab-turbo-lab46-isolated-d1-v1'));
  assert.match(guardCode,/env\?\.LAB46_ISOLATED_STAGING\s*!==\s*'1'/);
  assert.match(guardCode,/env\?\.LAB46_ISOLATED_DB_NAME\s*!==\s*STAGING_DB/);
  assert.match(guardCode,/LAB46_WRITE_TOKEN/);
  assert.match(guardCode,/SELECT marker FROM lab46_staging_sentinel/);
  assert.match(workerCode,/stagingCloudWriteGate\(request,\s*env\)/);
  assert.match(runtime,/stagingCloudWriteGate\(request,\s*env\)/);
  assert.doesNotMatch(JSON.stringify(stage),/LAB46_WRITE_TOKEN|CLOUDFLARE_API_TOKEN|GEMINI_API_KEY/);
  return binding;
}

validate(stageTemplate,production,bootstrap,authCode,brainCode,runtimeCode);

const mutations=[
  ['production D1 ID substituted',stage=>stage.d1_databases[0].database_id=PINNED_PRODUCTION_D1_ID],
  ['staging DB renamed',stage=>stage.d1_databases[0].database_name='musiclab-intelligence-db'],
  ['Worker name replaced',stage=>stage.name='musiclab-music-intelligence'],
  ['staging mode disabled',stage=>stage.vars.LAB46_ISOLATED_STAGING='0'],
  ['additional DB binding inserted',stage=>stage.d1_databases.push({...stage.d1_databases[0]})],
  ['secret put in tracked configuration',stage=>stage.vars.LAB46_WRITE_TOKEN='DO_NOT_EMBED_SECRETS_IN_REPOSITORY'],
];
for(const [label,mutate] of mutations){
  const candidate=JSON.parse(JSON.stringify(stageTemplate));
  mutate(candidate);
  assert.throws(()=>validate(candidate,production,bootstrap,authCode,brainCode,runtimeCode),
    undefined,'rejected mutation: '+label);
}

// Current bootstrap has only the sentinel and sync receipts, not the memory/archive
// tables used by the existing Worker. Never infer readiness from sentinel alone.
const memorySchemaMissing = !/\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?works\b/i.test(bootstrap) ||
  !/\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?versions\b/i.test(bootstrap);
assert.equal(memorySchemaMissing,true,
  're-evaluate production-compatible isolated schema before enabling remote sync');
assert.match(brainCode,/\bFROM works\b/);
assert.match(brainCode,/\bFROM versions\b/);

const output={
  check:'MUSICLAB_LAB46_STAGING_WORKER_BINDING_PREP',
  result:'PASS_PREPARATION_NOT_DEPLOYMENT',
  verifiedAccountId:VERIFIED_ACCOUNT_ID,
  stagingD1Name:STAGING_NAME,
  stagingD1Uuid:VERIFIED_STAGING_D1_ID,
  productionBindingUntouched:true,
  productionUuidDifferent:true,
  localPositiveChecks:1,
  localNegativeChecks:mutations.length,
  sentinelAndReceiptsAlreadyOwnerVerified:true,
  completeMemorySchemaPresentInStagingBootstrap:false,
  deploymentReady:false,
  authenticatedDeviceTransportVerified:false,
  remoteD1Writes:0,
  workerDeploys:0,
  nextGate:'ISOLATED_SCHEMA_AND_AUTH_REVIEW_PLUS_EXPLICIT_DEPLOY_APPROVAL',
};
console.log(JSON.stringify(output,null,2));
if(process.env.GITHUB_STEP_SUMMARY){
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, [
    '## LAB46 Worker staging binding readiness (local only)',
    '| Check | Result |','|---|---|',
    '| Verified staging UUID and unique binding | PASS |',
    '| Local negative configuration tests | '+mutations.length+'/'+mutations.length+' PASS |',
    '| Memory/archive schema deployed | NOT VERIFIED / INCOMPLETE |',
    '| Worker deploy allowed | NO, separate approval required |',
    '| Cloudflare mutations performed | NONE |',''
  ].join('\n'));
}
