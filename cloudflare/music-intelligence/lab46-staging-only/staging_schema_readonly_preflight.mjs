/**
 * LAB46 isolated D1 SCHEMA READBACK via dedicated D1 READ token.
 * POST /query is used for SQL SELECT only, supported by Cloudflare D1 Read.
 * This script CANNOT create, alter, delete, insert, deploy or import anything.
 */
const token=process.env.CLOUDFLARE_API_TOKEN||'';
const account=process.env.CLOUDFLARE_ACCOUNT_ID||'';
const ACCOUNT='5781b597517f6f7fe25751170a99b9ab';
const STAGING='e66bd500-cdaa-453c-a81c-c624a9d2611e';
const TARGET='musiclab-turbo-lab46-staging';
const PROD='7f36c6a5-5361-47cf-8520-b2ba398ea731';
const SENTINEL='musiclab-turbo-lab46-isolated-d1-v1';
const result={
  operation:'LAB46_STAGING_SCHEMA_SELECT_ONLY',
  mode:'D1_READ_TOKEN_ONLY',
  accountMatches:account===ACCOUNT,
  stagingUuidPinned:STAGING!==PROD,
  stagingNameVerified:false,
  expectedBootstrapTablesOnly:false,
  sentinelValueVerified:false,
  receiptsIndexVerified:false,
  existingReceiptCount:null,
  allowedToMigrate:false,
  remoteWrites:0,
  workerDeploys:0,
  errors:[],
};
const endpoint='https://api.cloudflare.com/client/v4/accounts/'+ACCOUNT+
  '/d1/database/'+STAGING;
async function cloudflare(path,method,sql){
  if(!token||account!==ACCOUNT)throw Error('ACCOUNT_OR_TOKEN_INVALID');
  if(method==='POST'){
    if(![
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
      "SELECT marker FROM lab46_staging_sentinel ORDER BY marker LIMIT 3",
      "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_lab46_sync_receipts_fingerprint'",
      "SELECT COUNT(*) AS n FROM lab46_sync_receipts",
    ].includes(sql))throw Error('NON_WHITELISTED_READONLY_SQL');
  }else if(method!=='GET')throw Error('METHOD_REJECTED');
  const response=await fetch(endpoint+path,{
    method,headers:{
      authorization:'Bearer '+token,
      accept:'application/json',
      ...(method==='POST'?{'content-type':'application/json'}:{})
    },
    ...(method==='POST'?{body:JSON.stringify({sql})}:{}),
    signal:AbortSignal.timeout(17000),
  });
  if(!response.ok)throw Error('API_READ_HTTP_'+response.status);
  const data=await response.json();
  if(data?.success!==true)throw Error('API_READ_UNSUCCESSFUL');
  return method==='GET'?data.result:(Array.isArray(data.result)?data.result[0]:data.result);
}
async function select(sql){
  const batch=await cloudflare('/query','POST',sql);
  if(!batch||batch.success===false||!Array.isArray(batch.results))
    throw Error('NO_SELECT_RESULTS');
  return batch.results;
}
try{
  if(!result.accountMatches||!result.stagingUuidPinned)
    throw Error('INVALID_PINNED_SCOPE');
  const database=await cloudflare('','GET');
  if(database?.uuid!==STAGING||database?.name!==TARGET)
    throw Error('TARGET_DATABASE_IDENTITY_MISMATCH');
  result.stagingNameVerified=true;
  const tables=(await select(
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
  )).map(x=>x.name).sort();
  result.observedSchemaTableCount=tables.length;
  result.observedSchemaTableNames=tables.slice(0,20).map(s=>String(s).slice(0,70));
  // Cloudflare reserves _cf_KV for its internal storage; it is not an app table.
  // Exclude this ONE documented system name, never arbitrary unexpected tables.
  const userTables=tables.filter(name=>name!=='_cf_KV');
  result.cloudflareInternalKvPresent=tables.includes('_cf_KV');
  result.expectedBootstrapTablesOnly=JSON.stringify(userTables)===
    JSON.stringify(['lab46_staging_sentinel','lab46_sync_receipts']);
  const markers=(await select(
    "SELECT marker FROM lab46_staging_sentinel ORDER BY marker LIMIT 3"
  )).map(x=>x.marker);
  result.sentinelValueVerified=markers.length===1 && markers[0]===SENTINEL;
  const indices=await select(
    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_lab46_sync_receipts_fingerprint'"
  );
  result.receiptsIndexVerified=indices.length===1 &&
    indices[0].name==='idx_lab46_sync_receipts_fingerprint';
  const receipts=await select("SELECT COUNT(*) AS n FROM lab46_sync_receipts");
  result.existingReceiptCount=receipts.length===1?Number(receipts[0].n):null;
  result.allowedToMigrate=result.stagingNameVerified &&
    result.expectedBootstrapTablesOnly &&
    result.sentinelValueVerified &&
    result.receiptsIndexVerified &&
    result.existingReceiptCount===0;
  if(!result.allowedToMigrate)
    result.errors.push('STAGING_SCHEMA_PRECONDITION_FAILED_STOP_MANUAL_MIGRATION');
}catch(e){result.errors.push(String(e.message||e).slice(0,130));}
console.log(JSON.stringify(result,null,2));
if(process.env.GITHUB_STEP_SUMMARY){
  const fs=await import('node:fs');
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    '\n### LAB46 real D1 schema SELECT-only audit\n' +
    '- Expected isolated stage schema: **'+(result.allowedToMigrate?'PASS':'STOP')+'**\n'+
    '- Remote D1 writes / deploy: **0 / 0**\n');
}
if(!result.allowedToMigrate)process.exitCode=1;
