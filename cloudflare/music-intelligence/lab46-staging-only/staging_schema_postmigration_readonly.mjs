/**
 * LAB46 exact staging D1 schema post-migration attestation.
 * Remote calls are restricted to GET and four hardcoded SQL SELECT statements.
 * No schema writes, data writes, deploy, secret modifications or paid API calls.
 */
const ACCOUNT='5781b597517f6f7fe25751170a99b9ab';
const UUID='e66bd500-cdaa-453c-a81c-c624a9d2611e';
const DB_NAME='musiclab-turbo-lab46-staging';
const PRODUCTION='7f36c6a5-5361-47cf-8520-b2ba398ea731';
const SENTINEL='musiclab-turbo-lab46-isolated-d1-v1';
const token=process.env.CLOUDFLARE_API_TOKEN||'';
const account=process.env.CLOUDFLARE_ACCOUNT_ID||'';
const endpoint='https://api.cloudflare.com/client/v4/accounts/'+ACCOUNT+'/d1/database/'+UUID;
const EXPECTED_TABLES=[
  'albums','artists','coverage_cells','decision_history',
  'lab46_staging_sentinel','lab46_sync_receipts','playback_bindings',
  'related_works','requests_log','research_state','search_missions',
  'version_evidence','versions','work_aliases','works'
].sort();
const REQUIRED_INDEXES=[
  'idx_lab46_sync_receipts_fingerprint',
  'idx_versions_work_category','idx_versions_artist','idx_playback_work',
  'idx_works_resolver_version','idx_works_iswc','idx_works_mb_work',
  'idx_versions_status_scores','idx_aliases_unique','idx_aliases_work_language',
  'idx_related_works_work','idx_evidence_version','idx_evidence_work_source',
  'idx_decision_history_version','idx_search_missions_work_family',
  'idx_coverage_work_family','idx_versions_work_round_position',
];
const REQUIRED_WORK_COLUMNS=[
  'resolver_version','work_signature_json','iswc','musicbrainz_work_id','ipi_json'
];
const REQUIRED_VERSION_COLUMNS=[
  'same_work_score','version_type_score','decision_status','ai_reason',
  'user_verified','user_rejected','cover_url','release_date','country',
  'source_names_json','source_url','credits_detail_json','video_title',
  'recording_fingerprint','legacy_score_20','musiclab_score_100',
  'cover_round','round_position','work_relation_confirmed',
];
const SQL=Object.freeze({
  tables:"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
  indexes:"SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name",
  works:"SELECT name FROM pragma_table_info('works') ORDER BY name",
  versions:"SELECT name FROM pragma_table_info('versions') ORDER BY name",
  sentinel:"SELECT marker FROM lab46_staging_sentinel ORDER BY marker LIMIT 3",
  receipts:"SELECT COUNT(*) AS n FROM lab46_sync_receipts",
  receiptStates:"SELECT state,COUNT(*) AS n,SUM(CASE WHEN version_id IS NOT NULL AND LENGTH(TRIM(version_id))>0 THEN 1 ELSE 0 END) AS versioned FROM lab46_sync_receipts GROUP BY state ORDER BY state",
  uniqueRevisions:"SELECT COUNT(DISTINCT revision) AS n FROM lab46_sync_receipts",
});
const report={
  check:'LAB46_STAGING_POSTMIGRATION_SCHEMA_READONLY',
  accountMatches:account===ACCOUNT,
  stagingUuid:UUID,
  stagingNameVerified:false,
  expectedApplicationTableCount:EXPECTED_TABLES.length,
  applicationTableCount:null,
  exactApplicationTables:false,
  requiredIndexesPresent:false,
  requiredWorkColumnsPresent:false,
  requiredVersionColumnsPresent:false,
  sentinelValid:false,
  receiptsCount:null,
  receiptStates:null,
  uniqueRevisionsCount:null,
  receiptLedgerIntegrityVerified:false,
  stageSchemaFullyVerified:false,
  missingTables:[],missingIndexes:[],missingWorkColumns:[],missingVersionColumns:[],
  remoteWrites:0,workerDeploys:0,errors:[],
};
async function call(method,sql){
  if(account!==ACCOUNT||UUID===PRODUCTION||!token)throw Error('SCOPE_OR_READ_TOKEN_INVALID');
  if(!['GET','POST'].includes(method))throw Error('INVALID_METHOD');
  if(method==='POST'&&!Object.values(SQL).includes(sql))throw Error('NOT_A_WHITELISTED_SELECT');
  const response=await fetch(endpoint+(method==='POST'?'/query':''),{
    method,
    headers:{
      authorization:'Bearer '+token,
      accept:'application/json',
      ...(method==='POST'?{'content-type':'application/json'}:{})
    },
    ...(method==='POST'?{body:JSON.stringify({sql})}:{}),
    signal:AbortSignal.timeout(17000)
  });
  if(!response.ok)throw Error('D1_READ_HTTP_'+response.status);
  const data=await response.json();
  if(data?.success!==true)throw Error('D1_READ_BAD_RESULT');
  if(method==='GET')return data.result;
  const batch=Array.isArray(data.result)?data.result[0]:data.result;
  if(!batch||batch.success===false||!Array.isArray(batch.results))
    throw Error('D1_SELECT_BAD_RESULT');
  return batch.results;
}
const missing=(required,actual)=>required.filter(x=>!actual.includes(x));
try{
  const db=await call('GET');
  report.stagingNameVerified=Boolean(db?.name===DB_NAME&&db?.uuid===UUID);
  if(!report.stagingNameVerified)throw Error('STAGING_NAME_UUID_MISMATCH');
  const tables=(await call('POST',SQL.tables)).map(r=>r.name)
    .filter(x=>x!=='_cf_KV').sort();
  report.applicationTableCount=tables.length;
  report.missingTables=missing(EXPECTED_TABLES,tables);
  report.exactApplicationTables=JSON.stringify(tables)===JSON.stringify(EXPECTED_TABLES);
  // Avoid SELECTs referencing absent memory/archive tables.
  if(!report.exactApplicationTables)throw Error('PARTIAL_OR_UNEXPECTED_TABLE_LAYOUT_STOP');
  const indexes=(await call('POST',SQL.indexes)).map(r=>r.name);
  report.missingIndexes=missing(REQUIRED_INDEXES,indexes);
  report.requiredIndexesPresent=report.missingIndexes.length===0;
  const works=(await call('POST',SQL.works)).map(r=>r.name);
  report.missingWorkColumns=missing(REQUIRED_WORK_COLUMNS,works);
  report.requiredWorkColumnsPresent=report.missingWorkColumns.length===0;
  const versions=(await call('POST',SQL.versions)).map(r=>r.name);
  report.missingVersionColumns=missing(REQUIRED_VERSION_COLUMNS,versions);
  report.requiredVersionColumnsPresent=report.missingVersionColumns.length===0;
  const markers=(await call('POST',SQL.sentinel)).map(r=>r.marker);
  report.sentinelValid=markers.length===1&&markers[0]===SENTINEL;
  const receipts=await call('POST',SQL.receipts);
  report.receiptsCount=receipts.length===1?Number(receipts[0].n):null;
  const states=await call('POST',SQL.receiptStates);
  const unique=await call('POST',SQL.uniqueRevisions);
  report.uniqueRevisionsCount=unique.length===1?Number(unique[0].n):null;
  report.receiptStates=states.map(row=>({
    state:String(row.state),count:Number(row.n),versioned:Number(row.versioned)
  }));
  const statesValid=report.receiptStates.every(s=>
    ['pending','failed','committed'].includes(s.state) &&
    Number.isSafeInteger(s.count)&&s.count>=0 &&
    Number.isSafeInteger(s.versioned)&&s.versioned>=0&&s.versioned<=s.count &&
    (s.state!=='committed'||s.versioned===s.count));
  const total=report.receiptStates.reduce((n,s)=>n+s.count,0);
  report.receiptLedgerIntegrityVerified=statesValid&&
    Number.isSafeInteger(report.receiptsCount)&&report.receiptsCount>=0 &&
    report.uniqueRevisionsCount===report.receiptsCount &&
    total===report.receiptsCount;
  report.stageSchemaFullyVerified=report.stagingNameVerified&&
    report.exactApplicationTables&&report.requiredIndexesPresent&&
    report.requiredWorkColumnsPresent&&report.requiredVersionColumnsPresent&&
    report.sentinelValid&&report.receiptLedgerIntegrityVerified;
  if(!report.stageSchemaFullyVerified)
    report.errors.push('STAGING_SCHEMA_INCOMPLETE_OR_UNEXPECTED');
}catch(error){
  report.errors.push(String(error?.message||error).slice(0,140));
}
console.log(JSON.stringify(report,null,2));
if(process.env.GITHUB_STEP_SUMMARY){
  const {appendFileSync}=await import('node:fs');
  appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    '\n## MusicLab LAB46 post-migration D1 READ audit\n' +
    '- Exact isolated staging schema: **'+(report.stageSchemaFullyVerified?'PASS':'STOP')+'**\n' +
    '- App tables: **'+report.applicationTableCount+'/'+EXPECTED_TABLES.length+'**\n'+
    '- Required indexes: **'+(report.requiredIndexesPresent?'PASS':'STOP')+'**\n'+
    '- Required memory/archive columns: **'+
      (report.requiredWorkColumnsPresent&&report.requiredVersionColumnsPresent?'PASS':'STOP')+'**\n'+
    '- Sentinel: **'+(report.sentinelValid?'PASS':'STOP')+'**\n'+
    '- Remote writes / deploy: **0 / 0**\n'
  );
}
if(!report.stageSchemaFullyVerified)process.exitCode=1;
