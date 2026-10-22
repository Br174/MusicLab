/**
 * LAB46 isolated staging gate, no new auth provider or external billable service.
 * Never embed these server secrets into an Android APK or a repo.
 *
 * IMPORTANT: this does not authorize production D1 writes. Legacy production
 * endpoints are unchanged unless LAB46_ISOLATED_STAGING=1 is explicitly set.
 */
const STAGING_DB = 'musiclab-turbo-lab46-staging';
async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
}
function safeEqual(a,b) {
  if (a.length !== b.length) return false;
  let mismatch=0;
  for(let i=0;i<a.length;i++) mismatch |= a[i]^b[i];
  return mismatch===0;
}
export async function stagingCloudWriteGate(request, env) {
  if (env?.LAB46_ISOLATED_STAGING !== '1') {
    return { allowed:false, status:403, reason:'staging_only_not_enabled' };
  }
  if (env?.LAB46_ISOLATED_DB_NAME !== STAGING_DB || !env?.DB) {
    return { allowed:false, status:503, reason:'isolated_db_binding_not_verified' };
  }
  const secret = typeof env?.LAB46_WRITE_TOKEN === 'string'
    ? env.LAB46_WRITE_TOKEN : '';
  if (secret.length < 32) {
    return { allowed:false, status:503, reason:'staging_secret_missing' };
  }
  const authorization = request.headers.get('authorization') || '';
  if (!authorization.startsWith('Bearer ') || authorization.length > 512) {
    return { allowed:false, status:401, reason:'missing_bearer_token' };
  }
  const candidate = authorization.slice(7);
  if (candidate.length < 32) {
    return { allowed:false, status:401, reason:'invalid_token' };
  }
  const [actual,expected]=await Promise.all([
    sha256(new TextEncoder().encode(candidate)),
    sha256(new TextEncoder().encode(secret)),
  ]);
  if(!safeEqual(actual,expected)){
    return { allowed:false, status:401, reason:'invalid_token' };
  }
  // A string in wrangler vars does not prove the bound D1 is isolated.
  // Only the staging-only sentinel (never part of production migrations) does.
  let proof;
  try {
    proof=await env.DB.prepare(
      'SELECT marker FROM lab46_staging_sentinel WHERE marker=?1 LIMIT 1'
    ).bind('musiclab-turbo-lab46-isolated-d1-v1').first();
  } catch {
    return { allowed:false, status:503, reason:'staging_db_sentinel_missing' };
  }
  if (proof?.marker !== 'musiclab-turbo-lab46-isolated-d1-v1') {
    return { allowed:false, status:503, reason:'staging_db_sentinel_missing' };
  }
  return { allowed:true, status:200, reason:'staging_authenticated' };
}
