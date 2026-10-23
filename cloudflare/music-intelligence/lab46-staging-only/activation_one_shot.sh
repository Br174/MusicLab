#!/usr/bin/env bash
# Inert until a SEPARATE OWNER AUTHORIZATION is represented by a new,
# reviewed, single-file activation-trigger-approved.json commit on LAB46.
# This script MUST NOT run during regular LAB, PR, build or schema CI.
set -euo pipefail
set +x
ROOT=cloudflare/music-intelligence
TRIGGER=$ROOT/lab46-staging-only/activation-trigger-approved.json
BRANCH=uab-build/musiclab-turbo-lab46-cloud-behavior-progressive-family-02
ACCOUNT=5781b597517f6f7fe25751170a99b9ab
WORKER=musiclab-turbo-lab46-isolated-staging

[[ "$GITHUB_REF_NAME" == "$BRANCH" && "$GITHUB_EVENT_NAME" == push ]] || exit 12
[[ -f "$TRIGGER" && -n "$LAB46_STAGING_CF_DEPLOY_TOKEN" &&
   -n "$LAB46_D1_READ_TOKEN" ]] || exit 13
[[ "$CLOUDFLARE_ACCOUNT_ID" == "$ACCOUNT" ]] || exit 14
[[ "$(git diff --name-only HEAD^ HEAD)" == "$TRIGGER" ]] || exit 15

# Only owner-verified public SPKI can be passed. Require a single verified
# LAB source SHA parent, no production/billable/DB migration authorizations.
node --input-type=module <<'JS'
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const path='cloudflare/music-intelligence/lab46-staging-only/';
const approval=JSON.parse(readFileSync(path+'activation-trigger-approved.json','utf8'));
const parent=execFileSync('git',['rev-parse','HEAD^'],{encoding:'utf8'}).trim();
const expected={
 scope:'SINGLE_MUSICLAB_TURBO_LAB46_STAGING_WORKER_ONLY',
 owner_approved:true,
 workers_free_confirmed:true,
 permit_update_existing_staging_worker:true,
 permit_workers_dev_staging_https:true,
 production_writes_allowed:false,
 d1_migrations_allowed:false,
 paid_services_allowed:false
};
for(const [k,v] of Object.entries(expected))
 if(approval[k]!==v)throw Error('MISSING_EXPLICIT_OWNER_APPROVAL_'+k);
// A trigger is proof of owner approval, not a place to commit passwords,
// Worker tokens, other resource IDs, or unrelated instructions.
const fields=new Set([...Object.keys(expected),
 'approved_source_head','confirmed_device_key_id','device_public_spki']);
if(Object.keys(approval).some(k=>!fields.has(k)))
 throw Error('EXTRA_APPROVAL_FIELDS_OR_POSSIBLE_SECRET_REJECTED');
if(approval.approved_source_head!==parent)
 throw Error('SOURCE_SHA_CHANGED_AFTER_APPROVAL');
if(!/^[A-Za-z0-9_-]{43}$/.test(approval.confirmed_device_key_id||''))
 throw Error('OWNER_FINGERPRINT_MISSING');
// The owner's PUBLIC key is stored only in this explicitly approved, single
// file trigger. This removes the manual GitHub Actions VARIABLE setup.
// No private key or privileged token may appear in the trigger.
const spki=approval.device_public_spki||'';
if(!/^[A-Za-z0-9_-]{90,240}$/.test(spki))
 throw Error('PUBLIC_SPKI_MISSING_OR_MALFORMED');
const keyId=createHash('sha256').update(Buffer.from(spki,'base64url')).digest('base64url');
if(keyId!==approval.confirmed_device_key_id)
 throw Error('PUBLIC_SPKI_FINGERPRINT_NOT_CONFIRMED');
const privateConfig=JSON.parse(readFileSync(path+'wrangler.staging.verified.jsonc','utf8'));
const publicConfig=JSON.parse(readFileSync(path+'wrangler.staging.https-activation.jsonc','utf8'));
const expectedPublic={...privateConfig,workers_dev:true,preview_urls:false};
if(JSON.stringify(publicConfig)!==JSON.stringify(expectedPublic))
 throw Error('STAGING_CONFIG_DIFFERENCE_NOT_APPROVED');
if(privateConfig.name!=='musiclab-turbo-lab46-isolated-staging'||
 privateConfig.workers_dev!==false||
 privateConfig.d1_databases?.length!==1||
 privateConfig.d1_databases[0].database_id!=='e66bd500-cdaa-453c-a81c-c624a9d2611e'||
 privateConfig.vars?.LAB46_ISOLATED_STAGING!=='1'||
 privateConfig.main!=='worker-entry.mjs'||
 privateConfig.route||privateConfig.routes||privateConfig.triggers)
 throw Error('WORKER_TARGET_BOUNDARY_FAILED');
console.log('LAB46_SCOPE_AND_PUBLIC_OWNER_KEY_VERIFIED_NO_CREDENTIALS_PRINTED');
JS

# Read the non-secret PUBLIC SPKI from the approved trigger. It is deliberately
# not printed; the owner's private Android key never leaves Android Keystore.
LAB46_DEVICE_PUBLIC_SPKI=$(node -e '
const f=require("node:fs");const j=JSON.parse(f.readFileSync(process.argv[1],"utf8"));
process.stdout.write(j.device_public_spki||"")
' "$TRIGGER")
export LAB46_DEVICE_PUBLIC_SPKI

# D1 proof via the EXISTING READ-ONLY token before any write to Workers.
CLOUDFLARE_API_TOKEN="$LAB46_D1_READ_TOKEN" \
  node "$ROOT/lab46-staging-only/staging_schema_postmigration_readonly.mjs"
# Use ONLY an existing owner-prepared staging Worker. A per-Worker Editor token
# cannot create new Workers and has no permissions on unrelated Workers.
http=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
 -H "Authorization: Bearer $LAB46_STAGING_CF_DEPLOY_TOKEN" \
 "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/workers/scripts/$WORKER")
[[ "$http" == 200 ]] || { echo "STAGING_WORKER_NOT_PROVEN_EXISTING_$http"; exit 16; }
# The owners' verified workers.dev account name is pinned from their dashboard.
# Do not request account-wide /workers/subdomain: a single-Worker Editor token
# correctly lacks that permission. Instead verify the scoped script subdomain
# using the documented per-Worker GET, which is allowed for Individual Workers.
subdomain='brunoverlezza'
script_subdomain=$(curl --fail --silent --show-error \
 -H "Authorization: Bearer $LAB46_STAGING_CF_DEPLOY_TOKEN" \
 "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/workers/scripts/$WORKER/subdomain" \
 | node -e 'let b="";process.stdin.on("data",x=>b+=x);process.stdin.on("end",()=>{try{const j=JSON.parse(b);if(!j.success||typeof j?.result?.enabled!=="boolean")process.exit(2);process.stdout.write("VERIFIED")}catch{process.exit(2)}})')
[[ "$script_subdomain" == VERIFIED && "$subdomain" == brunoverlezza ]] || exit 17

cd "$ROOT"
# These two steps NEVER deploy or write secrets.
npx --yes wrangler@4 deploy --dry-run \
 --config lab46-staging-only/wrangler.staging.verified.jsonc \
 --outdir "$RUNNER_TEMP/lab46-private"
npx --yes wrangler@4 deploy --dry-run \
 --config lab46-staging-only/wrangler.staging.https-activation.jsonc \
 --outdir "$RUNNER_TEMP/lab46-https"

export CLOUDFLARE_API_TOKEN="$LAB46_STAGING_CF_DEPLOY_TOKEN"
# Cloudflare validates "secrets.required" BEFORE first deployment, and
# "wrangler secret put" immediately deploys a Worker version. Therefore
# provision under a private, ephemeral config with only REQUIRED declarations
# removed. The canonical config and its final, enforced secret contract remain
# untouched in GitHub. No secret values are ever written to this file.
PRIVATE_BOOTSTRAP_CFG="lab46-staging-only/.lab46-private-bootstrap-${GITHUB_RUN_ID:?}.jsonc"
node --input-type=module - "$PRIVATE_BOOTSTRAP_CFG" <<'JS'
import {readFileSync,writeFileSync} from 'node:fs';
const path=process.argv[2];
const base=JSON.parse(readFileSync(
 'lab46-staging-only/wrangler.staging.verified.jsonc','utf8'));
if(base.name!=='musiclab-turbo-lab46-isolated-staging' ||
 base.workers_dev!==false || base.main!=='worker-entry.mjs' ||
 base.d1_databases?.length!==1 ||
 base.d1_databases[0].database_id!=='e66bd500-cdaa-453c-a81c-c624a9d2611e' ||
 base.vars?.LAB46_ISOLATED_STAGING!=='1' ||
 JSON.stringify(base.secrets?.required)!==
 JSON.stringify(['LAB46_WRITE_TOKEN','LAB46_DEVICE_PUBLIC_SPKI']))
 throw Error('TEMP_BOOTSTRAP_ONLY_AFTER_EXACT_CONFIG_GUARD');
delete base.secrets;
writeFileSync(path,JSON.stringify(base,null,2)+'\n',{flag:'wx',mode:0o600});
JS
ROLLBACK_NEEDED=no
rollback(){
 if [[ "$ROLLBACK_NEEDED" == yes ]];then
  echo "STAGING_SMOKE_OR_DEPLOY_FAILED_DISABLE_ENDPOINT"
  # Bootstrap variant deliberately does not require all secrets, so rollback
  # is safe even if a later secret-put failed half-way.
  npx --yes wrangler@4 deploy --config "$PRIVATE_BOOTSTRAP_CFG" || true
 fi
 rm -f -- "$PRIVATE_BOOTSTRAP_CFG"
}
trap rollback EXIT
# First update ONLY the already-existing staging Worker with workers.dev
# disabled. The per-Worker Editor credential must not create any new Worker.
npx --yes wrangler@4 deploy --config "$PRIVATE_BOOTSTRAP_CFG"
ROLLBACK_NEEDED=yes
# Generate server-side random privileged bearer IN CI, never in Android or Git.
value=$(node -e "process.stdout.write(require('node:crypto').randomBytes(48).toString('base64url'))")
printf '%s' "$value" | npx --yes wrangler@4 secret put LAB46_WRITE_TOKEN \
 --config "$PRIVATE_BOOTSTRAP_CFG"
unset value
# PUBLIC owner-paired SPKI only. These operations keep workers.dev OFF.
printf '%s' "$LAB46_DEVICE_PUBLIC_SPKI" | npx --yes wrangler@4 secret put LAB46_DEVICE_PUBLIC_SPKI \
 --config "$PRIVATE_BOOTSTRAP_CFG"
# Only now do all required secrets exist: deploy the canonical public HTTPS
# staging config (no routes, no custom domains, never production).
npx --yes wrangler@4 deploy --config lab46-staging-only/wrangler.staging.https-activation.jsonc
origin="https://$WORKER.$subdomain.workers.dev"
# Authenticate-negative smoke only, so NEVER create musical decision rows.
read_http=$(curl --max-time 15 -sS -o /dev/null -w '%{http_code}' "$origin/api/v1/archive/search")
[[ "$read_http" == 405 ]] || exit 18
post_http=$(curl --max-time 15 -sS -o /dev/null -w '%{http_code}' \
 -H 'content-type: application/json' -d '{}' "$origin/api/v1/turbo/decision-sync")
[[ "$post_http" == 401 ]] || exit 19
ROLLBACK_NEEDED=no
echo "LAB46_EXISTING_STAGING_WORKER_UPDATED_WITH_OWNER_PAIRED_PUBLIC_KEY"
echo "HTTPS_ORIGIN=$origin"
echo "REMOTE_D1_SCHEMA_NOT_MODIFIED"
