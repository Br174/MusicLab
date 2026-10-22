/**
 * MusicLab LAB46: purely local staging configuration gate.
 * No network, no token, no Cloudflare API, no provisioning and no deploy.
 * This file must remain safe to execute in an untrusted PR CI runner.
 */
import assert from 'node:assert/strict';
import { readFileSync, appendFileSync } from 'node:fs';

const STAGING_DB = 'musiclab-turbo-lab46-staging';
const STAGING_WORKER = 'musiclab-turbo-lab46-isolated-staging';
const PLACEHOLDER = 'REPLACE_WITH_REAL_ISOLATED_STAGING_D1_ID';
const root = process.cwd();
const local = (path) => readFileSync(root + '/' + path, 'utf8');

function validate(stagingSource, productionSource, bootstrapSource) {
  const staging = JSON.parse(stagingSource);
  const production = JSON.parse(productionSource);
  assert.equal(staging.name, STAGING_WORKER, 'wrong staging worker');
  assert.equal(staging.main, 'src/worker-v20-runtime.js', 'worker source unexpectedly changed');
  assert.equal(staging.vars?.LAB46_ISOLATED_STAGING, '1', 'staging gate disabled');
  assert.equal(staging.vars?.LAB46_ISOLATED_DB_NAME, STAGING_DB, 'isolation label mismatch');
  assert.equal(staging.d1_databases?.length, 1, 'exactly one staging D1 binding required');
  const db = staging.d1_databases[0];
  assert.equal(db.binding, 'DB', 'runtime DB binding mismatch');
  assert.equal(db.database_name, STAGING_DB, 'staging DB name mismatch');
  // Preparation is not provisioning. Never check a concrete remote D1 ID into the template.
  assert.equal(db.database_id, PLACEHOLDER, 'unexpected real or shared database ID');
  const productionDbs = production.d1_databases || [];
  assert.ok(productionDbs.length > 0, 'cannot compare against production');
  for (const real of productionDbs) {
    assert.notEqual(db.database_name, real.database_name, 'production DB name reuse');
    assert.notEqual(db.database_id, real.database_id, 'production DB ID reuse');
  }
  const sql = bootstrapSource.replace(/--[^\n]*(?:\n|$)/g, '\n');
  const statements = sql.split(';').map(s => s.trim()).filter(Boolean);
  const allowed = [
    /^CREATE TABLE IF NOT EXISTS lab46_staging_sentinel\s*\(/i,
    /^INSERT OR IGNORE INTO lab46_staging_sentinel\s*\(/i,
    /^CREATE TABLE IF NOT EXISTS lab46_sync_receipts\s*\(/i,
    /^CREATE INDEX IF NOT EXISTS idx_lab46_sync_receipts_fingerprint\s+ON lab46_sync_receipts\s*\(/i,
  ];
  assert.equal(statements.length, allowed.length, 'unexpected bootstrap statements');
  for (let i = 0; i < allowed.length; i++) {
    assert.match(statements[i], allowed[i], 'unexpected staging bootstrap statement');
  }
  assert.match(sql, /musiclab-turbo-lab46-isolated-d1-v1/, 'staging-only sentinel missing');
  assert.doesNotMatch(sql, /\b(?:DROP|ALTER|ATTACH|DETACH|REPLACE|DELETE|UPDATE|TRUNCATE)\b/i,
    'destructive or unrelated SQL in staging bootstrap');
  assert.doesNotMatch(sql, /musiclab-intelligence-db/i, 'production name in staging SQL');
  return { isolatedWorker: staging.name, intendedD1Name: db.database_name,
    approvedProductionMutations: 0, remoteWrites: 0, deployment: false,
    databaseProvisioned: false, productionD1Unchanged: true };
}

const stagePath = 'cloudflare/music-intelligence/wrangler.lab46-staging.template.jsonc';
const productionPath = 'cloudflare/music-intelligence/wrangler.jsonc';
const bootstrapPath = 'cloudflare/music-intelligence/lab46-staging-only/bootstrap.sql';
const stagingSource = local(stagePath);
const productionSource = local(productionPath);
const bootstrapSource = local(bootstrapPath);
const result = validate(stagingSource, productionSource, bootstrapSource);

const invalidCases = [
  ['production_binding', JSON.stringify({
    ...JSON.parse(stagingSource),
    d1_databases: JSON.parse(productionSource).d1_databases,
  }), productionSource, bootstrapSource],
  ['disabled_staging', JSON.stringify({
    ...JSON.parse(stagingSource),
    vars: { ...JSON.parse(stagingSource).vars, LAB46_ISOLATED_STAGING:'0' },
  }), productionSource, bootstrapSource],
  ['unexpected_database_id', stagingSource.replace(PLACEHOLDER,
    '7f36c6a5-5361-47cf-8520-b2ba398ea731'), productionSource, bootstrapSource],
  ['unsafe_bootstrap', stagingSource, productionSource,
    bootstrapSource + '\nDROP TABLE versions;\n'],
  ['unexpected_table', stagingSource, productionSource,
    bootstrapSource.replace('lab46_staging_sentinel', 'production_versions')],
];
for (const [name, stage, prod, sql] of invalidCases) {
  assert.throws(() => validate(stage, prod, sql), undefined, name + ': failure expected');
}
console.log(JSON.stringify({
  check: 'LAB46_STAGING_PREP_ONLY',
  result: 'PASS',
  localPositiveChecks: 1,
  localNegativeChecks: invalidCases.length,
  ...result,
  approvalGate: 'REQUIRED_BEFORE_CREATE_OR_DEPLOY',
}, null, 2));
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    '\n### LAB46 staging preparation (no remote side effects)\n' +
    '- Local config/bootstrap guard: **PASS** (1 positive + ' + invalidCases.length + ' negative tests)\n' +
    '- Database created: **NO**; deploy: **NO**; D1 write: **NO**\n' +
    '- Required next step: explicit isolated-staging authorization and real Cloudflare create capability.\n');
}
