import fs from 'node:fs';
const token=process.env.CLOUDFLARE_API_TOKEN || '';
const account=process.env.CLOUDFLARE_ACCOUNT_ID || '';
const audit={
  goal:'LAB46_ISOLATED_D1_CAPACITY',
  mode:'READ_ONLY_NO_PROVIDER_MUTATION',
  connected:false,freePlanProven:false,
  stagingPresent:false,productionDbPresent:false,
  allowedToProvision:false,
  existingD1Count:null,freeDatabaseLimit:10,errors:[],
  accountPlanEvidence:'UNKNOWN',
};
const record=()=>{
  const safe={...audit};
  console.log(JSON.stringify(safe,null,2));
  if(process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    [
      '## MusicLab LAB46 Cloudflare (read-only)',
      '| Check | Result |','|---|---|',
      '| Token API accessible | '+(safe.connected?'yes':'no')+' |',
      '| Existing isolated D1 | '+(safe.stagingPresent?'yes':'not found / unavailable')+' |',
      '| Shared production D1 | '+(safe.productionDbPresent?'visible (never touched)':'not visible / unverified')+' |',
      '| Existing D1 count | '+(safe.existingD1Count??'unknown')+' |',
      '| Verified Workers Free plan | '+(safe.freePlanProven?'yes':'UNKNOWN, stop before creating')+' |',
      '| This workflow creates/deploys anything | **NO** |',
      '| Errors | '+(safe.errors.join('; ')||'none')+' |',
      '',
    ].join('\n'));
  }
};
if(token.length<10 || !/^[a-f0-9]{32}$/i.test(account)){
  audit.errors.push('CLOUDFLARE_TOKEN_OR_ACCOUNT_NOT_AVAILABLE');
  record();
  process.exitCode=2;
} else {
  const fetchReadOnly=async path=>{
    try{
      const res=await fetch('https://api.cloudflare.com/client/v4/'+path,{
        method:'GET',
        headers:{Authorization:'Bearer '+token,Accept:'application/json'},
        signal:AbortSignal.timeout(18000),
      });
      if(!res.ok)return {http:res.status,success:false,result:null};
      const value=await res.json();
      return {http:res.status,success:value?.success===true,result:value?.result,
        result_info:value?.result_info};
    }catch{return {http:0,success:false,result:null};}
  };
  const [verify,d1,accountInfo,subscriptions]=await Promise.all([
    fetchReadOnly('user/tokens/verify'),
    fetchReadOnly('accounts/'+account+'/d1/database?per_page=100'),
    fetchReadOnly('accounts/'+account),
    fetchReadOnly('accounts/'+account+'/subscriptions'),
  ]);
  audit.connected=verify.success && verify.result?.status==='active';
  if(!audit.connected)audit.errors.push('TOKEN_NOT_VERIFIED');
  if(d1.success && Array.isArray(d1.result)){
    audit.existingD1Count=d1.result_info?.total_count??d1.result.length;
    audit.stagingPresent=d1.result.some(db=>db.name==='musiclab-turbo-lab46-staging');
    audit.productionDbPresent=d1.result.some(db=>db.name==='musiclab-intelligence-db');
    if(audit.existingD1Count>=10)audit.errors.push('POSSIBLE_FREE_DATABASE_LIMIT_REACHED');
  }else audit.errors.push('D1_LIST_READ_DENIED_HTTP_'+d1.http);
  // Informative only; plan must be explicitly verified, never inferred from
  // an account having fewer than 10 databases or from a token role.
  const sub=Array.isArray(subscriptions.result)?subscriptions.result:[];
  // Workers Free is the default plan, but absence of a paid subscription does
  // NOT independently prove the current plan or authorize provisioning.
  // Report only sanitized booleans/counts: no subscription IDs or billing data.
  const listedSubscriptions=subscriptions.success && Array.isArray(subscriptions.result);
  const active=sub.filter(s=>!['cancelled','expired','failed'].includes(String(s?.state||'').toLowerCase()));
  const planLabel=s=>[
    s?.rate_plan?.id,s?.rate_plan?.name,s?.rate_plan?.public_name,s?.name,
  ].filter(Boolean).join(' ').toLowerCase();
  const workersRelated=active.filter(s=>/workers?/i.test(planLabel(s)));
  const workersPaid=workersRelated.some(s=>
    /workers?[\\s_-]*(paid|unbound)|(paid|unbound)[\\s_-]*workers?/.test(planLabel(s)) ||
    Number(s?.price)>0
  );
  const workersFreeExplicit=workersRelated.some(s=>
    /workers?[\\s_-]*free|free[\\s_-]*workers?/.test(planLabel(s))
  );
  audit.billingSubscriptionsReadable=listedSubscriptions;
  audit.billingSubscriptionCount=listedSubscriptions?sub.length:null;
  audit.workersSubscriptionCount=listedSubscriptions?workersRelated.length:null;
  audit.workersPaidDetected=listedSubscriptions?workersPaid:null;
  const accountType=String(accountInfo.result?.type||'').toLowerCase();
  if(workersPaid){
    audit.accountPlanEvidence='ACTIVE_WORKERS_PAID_SUBSCRIPTION_OBSERVED';
    audit.errors.push('WORKERS_PAID_SUBSCRIPTION_PRESENT_NO_ZERO_COST_ASSUMPTION');
  }else if(listedSubscriptions && workersFreeExplicit){
    audit.freePlanProven=true;
    audit.accountPlanEvidence='EXPLICIT_ACTIVE_WORKERS_FREE_SUBSCRIPTION';
  }else{
    audit.accountPlanEvidence=(listedSubscriptions?'NO_EXPLICIT_WORKERS_PLAN_IN_BILLING_LIST':('SUBSCRIPTION_READ_HTTP_'+subscriptions.http))+
      (accountType?'_ACCOUNT_METADATA_SEEN':'');
    audit.errors.push('WORKERS_FREE_PLAN_NOT_PROVEN');
  }
  audit.allowedToProvision=false;
  record();
  if(!audit.connected || !d1.success)process.exitCode=1;
}
