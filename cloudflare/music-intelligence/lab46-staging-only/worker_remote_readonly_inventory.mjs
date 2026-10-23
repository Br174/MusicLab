/**
 * LAB46 staging Worker inventory, STRICTLY read only.
 *
 * Existing D1 preflight credential may lack Workers:Read, in which case
 * report UNKNOWN rather than incorrectly concluding the Worker is absent.
 * Never inspect tokens, secrets or Worker code; never use a write method.
 */
const account='5781b597517f6f7fe25751170a99b9ab';
const worker='musiclab-turbo-lab46-isolated-staging';
const credential=process.env.CLOUDFLARE_API_TOKEN||'';
const pinnedAccount=process.env.CLOUDFLARE_ACCOUNT_ID||'';
const report={
  operation:'LAB46_STAGING_WORKER_REMOTE_GET_ONLY_INVENTORY',
  workerName:worker,
  accountMatches:pinnedAccount===account,
  workerPresence:'UNKNOWN',
  workerReadPermission:'UNKNOWN',
  workersDevSubdomain:'UNKNOWN',
  advertisedEndpoint:'NOT_VERIFIED',
  remoteWrites:0,
  deploys:0,
  secretReads:0,
  errors:[]
};
async function get(url){
  const r=await fetch(url,{
    method:'GET',headers:{Authorization:'Bearer '+credential,Accept:'application/json'},
    signal:AbortSignal.timeout(12000)
  });
  return r;
}
try{
  if(!report.accountMatches||credential.length<20)
    throw Error('READ_TOKEN_OR_ACCOUNT_MISSING');
  const root='https://api.cloudflare.com/client/v4/accounts/'+account;
  const item=await get(root+'/workers/scripts/'+worker);
  if(item.status===200){
    report.workerPresence='EXISTS';
    report.workerReadPermission='ALLOW';
  }else if(item.status===404){
    report.workerPresence='NOT_FOUND';
    report.workerReadPermission='ALLOW';
  }else if(item.status===401||item.status===403){
    report.workerReadPermission='DENIED';
    report.errors.push('WORKER_READ_SCOPE_UNAVAILABLE');
  }else{
    report.errors.push('WORKER_GET_HTTP_'+item.status);
  }
  const domain=await get(root+'/workers/subdomain');
  if(domain.status===200){
    const value=await domain.json().catch(()=>null);
    const name=String(value?.result?.subdomain||'');
    if(value?.success&&/^[a-z0-9-]{3,100}$/.test(name))
      report.workersDevSubdomain=name+'.workers.dev';
  }else if(domain.status===401||domain.status===403){
    report.errors.push('ACCOUNT_SUBDOMAIN_READ_SCOPE_UNAVAILABLE');
  }else report.errors.push('SUBDOMAIN_GET_HTTP_'+domain.status);
}catch(error){
  report.errors.push(String(error?.message||error).slice(0,90));
}
console.log(JSON.stringify(report,null,2));
if(process.env.GITHUB_STEP_SUMMARY){
  const {appendFileSync}=await import('node:fs');
  appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    '\n### Staging Worker remote GET-only inventory\n'+
    '- Staging Worker: **'+report.workerPresence+'**\n'+
    '- Workers:Read permission: **'+report.workerReadPermission+'**\n'+
    '- No remote D1/Worker writes or deploy\n'+
    '- Not proof of configured secrets, endpoint readiness or permission to deploy\n'
  );
}
