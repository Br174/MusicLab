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
   -n "$LAB46_DEVICE_PUBLIC_SPKI" && -n "$LAB46_D1_READ_TOKEN" ]] || exit 13
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
if(approval.approved_source_head!==parent)
 throw Error('SOURCE_SHA_CHANGED_AFTER_APPROVAL');
if(!/^[A-Za-z0-9_-]{43}$/.test(approval.confirmed_device_key_id||''))
 throw Error('OWNER_FINGERPRINT_MISSING');
const spki=process.env.LAB46_DEVICE_PUBLIC_SPKI||'';
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

# D1 proof via the EXISTING READ-ONLY token before any write to Workers.
CLOUDFLARE_API_TOKEN="$LAB46_D1_READ_TOKEN" \
  node "$ROOT/lab46-staging-only/staging_schema_postmigration_readonly.mjs"
# Use ONLY an existing owner-prepared staging Worker. A per-Worker Editor token
# cannot create new Workers and has no permissions on unrelated Workers.
http=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
 -H "Authorization: Bearer $LAB46_STAGING_CF_DEPLOY_TOKEN" \
 "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/workers/scripts/$WORKER")
[[ "$http" == 200 ]] || { echo "STAGING_WORKER_NOT_PROVEN_EXISTING_$http"; exit 16; }
subdomain=$(curl --fail-with-body --silent --show-error \
 -H "Authorization: Bearer $LAB46_STAGING_CF_DEPLOY_TOKEN" \
 "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/workers/subdomain" \
 | node -e 'let b="";process.stdin.on("data",x=>b+=x);process.stdin.on("end",()=>{try{const j=JSON.parse(b);const s=j?.result?.subdomain;if(!j.success||typeof s!=="string"||!/^[a-z0-9-]{3,100}$/.test(s))process.exit(2);process.stdout.write(s)}catch{process.exit(2)}})')
[[ -n "$subdomain" ]] || exit 17

cd "$ROOT"
# These two steps NEVER deploy or write secrets.
npx --yes wrangler@4 deploy --dry-run \
 --config lab46-staging-only/wrangler.staging.verified.jsonc \
 --outdir "$RUNNER_TEMP/lab46-private"
npx --yes wrangler@4 deploy --dry-run \
 --config lab46-staging-only/wrangler.staging.https-activation.jsonc \
 --outdir "$RUNNER_TEMP/lab46-https"

export CLOUDFLARE_API_TOKEN="$LAB46_STAGING_CF_DEPLOY_TOKEN"
ROLLBACK_NEEDED=no
rollback(){
 if [[ "$ROLLBACK_NEEDED" == yes ]];then
  echo "STAGING_NEGATIVE_SMOKE_FAILED_ATTEMPT_DISABLE_ENDPOINT"
  npx --yes wrangler@4 deploy --config lab46-staging-only/wrangler.staging.verified.jsonc || true
 fi
}
trap rollback EXIT
# First update ONLY the already-existing owner-prepared staging Worker;
# this disables its workers.dev route before provisioning secrets.
npx --yes wrangler@4 deploy --config lab46-staging-only/wrangler.staging.verified.jsonc
# Generate an unexported server-side 48-byte privileged bearer IN CI ONLY.
value=$(node -e "process.stdout.write(require('node:crypto').randomBytes(48).toString('base64url'))")
printf '%s' "$value" | npx --yes wrangler@4 secret put LAB46_WRITE_TOKEN \
 --config lab46-staging-only/wrangler.staging.verified.jsonc
unset value
# PUBLIC owner-paired SPKI only. Never transfer any private Android key.
printf '%s' "$LAB46_DEVICE_PUBLIC_SPKI" | npx --yes wrangler@4 secret put LAB46_DEVICE_PUBLIC_SPKI \
 --config lab46-staging-only/wrangler.staging.verified.jsonc
ROLLBACK_NEEDED=yes
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
