/**
 * Scudo360 Evolution r41: PURE advisory companion to existing r40 and the
 * single Change Compatibility Guard. No new scheduler, test runner, writes,
 * API calls, APK signing, autonomous merge or release authority.
 */
import {createHash} from 'node:crypto';

const SHA=/^[0-9a-f]{40}$/i;
const SAFE=/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))(?!.*\\)[a-zA-Z0-9_.\/-]+$/;
const list=v=>Array.isArray(v)?v:[];
const unique=a=>[...new Set(a)];
const fault=(code,detail='')=>({code,detail});
const safePath=s=>typeof s==='string'&&s.length>0&&SAFE.test(s)&&!s.includes('//');
const matches=(p,prefix)=>p===prefix||p.startsWith(prefix.endsWith('/')?prefix:prefix+'/');
const p95=a=>a[Math.ceil(a.length*0.95)-1];
const median=a=>a[Math.floor(a.length/2)];
const stable=(x)=>JSON.stringify(x,Object.keys(x||{}).sort());
const digest=o=>createHash('sha256').update(JSON.stringify(o)).digest('hex');

/** Fast targeted YAML smoke: detects the historical dangling scalar-run bug.
 * Not a complete YAML parser; the full native workflow runner remains owner. */
export function inspectWorkflowRunBlocks(source) {
  if(typeof source!=='string')return [{code:'WORKFLOW_SOURCE_NOT_TEXT',line:0}];
  const lines=source.split(/\r?\n/),issues=[];
  for(let i=0;i<lines.length-1;i++){
    const line=lines[i],match=line.match(/^(\s*)run:\s*(\S.*)$/);
    if(!match||/^[|>][+-]?(?:\s+#.*)?$/.test(match[2]))continue;
    let j=i+1;
    while(j<lines.length&&!lines[j].trim())j++;
    if(j>=lines.length)continue;
    const next=lines[j],indented=(next.match(/^ */)||[''])[0].length>match[1].length;
    if(indented&&next.trim()&&!next.trim().startsWith('#')){
      issues.push({code:'WORKFLOW_SCALAR_RUN_HAS_DANGLING_CHILD',line:j+1});
    }
  }
  return issues;
}


export function assessEvolutionR41({
  expectedCommit,manifest,r40Report,changedPaths=[],ownership=[],dependencies=[],
  behaviorRegistry=null,incidents=[],benchmarks=null,artifactReceipt=null,
  workflowSources={}
}={}) {
  const errors=[],warnings=[],diagnostics=[];
  const note=(code,detail='')=>errors.push(fault(code,detail));
  if(!SHA.test(expectedCommit||''))note('INVALID_TARGET_SHA');
  if(r40Report?.targetCommit!==expectedCommit||r40Report?.status!=='CI_BEFORE_AFTER_VERIFIED'||r40Report?.passed!==true)
    note('R40_EVIDENCE_NOT_GREEN_FOR_EXACT_COMMIT');
  if(!Array.isArray(changedPaths)||changedPaths.length===0)note('CHANGE_SET_MISSING');
  if(!Array.isArray(ownership)||ownership.length===0)note('OWNERSHIP_MAP_MISSING');
  if(!Array.isArray(dependencies))note('DEPENDENCIES_INVALID');
  if(!Array.isArray(incidents))note('INCIDENTS_INVALID');
  const paths=unique(list(changedPaths));
  if(paths.length!==list(changedPaths).length)note('DUPLICATE_CHANGED_PATH');
  for(const p of paths)if(!safePath(p))note('UNSAFE_CHANGED_PATH',String(p));
  for(const p of paths.filter(p=>p.startsWith('.github/workflows/')&&/\.ya?ml$/.test(p))){
    const source=workflowSources?.[p];
    if(typeof source!=='string'){note('CHANGED_WORKFLOW_SOURCE_REQUIRED_FOR_PREFLIGHT',p);continue;}
    for(const error of inspectWorkflowRunBlocks(source))note(error.code,p+':'+error.line);
  }
  const contracts=new Map();
  for(const c of list(manifest?.contracts)){
    if(!c||typeof c.id!=='string'||contracts.has(c.id))note('INVALID_OR_DUPLICATE_CONTRACT',String(c?.id));
    else contracts.set(c.id,c);
  }
  if(contracts.size===0)note('NO_CONTRACTS');
  const activeRules=new Map();
  if(behaviorRegistry!==null){
    if(!Array.isArray(behaviorRegistry?.rules))note('INVALID_BEHAVIOR_REGISTRY');
    for(const r of list(behaviorRegistry?.rules)){
      if(r?.status==='active'&&typeof r.id==='string')activeRules.set(r.id,r);
    }
  }
  const graph=new Map();
  for(const e of list(dependencies)){
    if(!contracts.has(e?.from)||!contracts.has(e?.to))note('UNRESOLVED_DEPENDENCY',String(e?.from)+':'+String(e?.to));
    else graph.set(e.from,unique([...(graph.get(e.from)||[]),e.to]));
  }
  const directlyAffected=new Set(),touches=[],unknown=[];
  for(const p of paths){
    if(!safePath(p))continue;
    const found=list(ownership).filter(o=>safePath(o?.prefix)&&matches(p,o.prefix));
    if(!found.length){unknown.push(p);note('UNMAPPED_CHANGED_PATH',p);continue;}
    const max=Math.max(...found.map(o=>o.prefix.length));
    const exact=found.filter(o=>o.prefix.length===max);
    if(exact.length!==1){note('AMBIGUOUS_OWNERSHIP_DELEGATE_TO_GUARD',p);continue;}
    const owned=exact[0];
    const cids=list(owned.contractIds);
    if(cids.length===0){note('OWNER_WITHOUT_CONTRACT',p);continue;}
    for(const id of cids){
      if(!contracts.has(id))note('OWNER_UNKNOWN_CONTRACT',p+':'+id);
      else directlyAffected.add(id);
    }
    touches.push({path:p,owner:owned.owner||'project',contractIds:cids.slice()});
  }
  const affected=new Set(directlyAffected),queue=[...directlyAffected],discovered=new Map();
  while(queue.length&&affected.size<=contracts.size){
    const from=queue.shift();
    for(const to of graph.get(from)||[]){
      if(affected.has(to))continue;
      affected.add(to);discovered.set(to,from);queue.push(to);
    }
  }
  if(affected.size>contracts.size)note('INVALID_IMPACT_GRAPH');
  const proven=new Set();
  for(const row of list(r40Report?.requests))for(const c of list(row.contracts)){
    if(c?.passed===true&&list(c.classes).length&&c.classes.every(x=>x.passed===true))proven.add(c.contractId);
  }
  const coverage=[];
  for(const id of affected){
    const c=contracts.get(id);
    const required=c?.priority==='P0'||c?.priority==='P1';
    const covered=proven.has(id);
    if(required&&!covered)note('IMPACTED_MANDATORY_CONTRACT_NOT_PROVEN',id);
    else if(!covered)warnings.push(fault('IMPACTED_OPTIONAL_CONTRACT_UNPROVEN',id));
    coverage.push({contractId:id,priority:c?.priority||'UNKNOWN',verified:covered,
      impact:directlyAffected.has(id)?'DIRECT':'DEPENDENCY',causedBy:discovered.get(id)||null});
  }
  if(affected.size===0)note('NO_PROVEN_IMPACT_MAP');
  const protectedRules=unique(list(ownership).filter(o=>touches.some(t=>matches(t.path,o?.prefix||'')))
    .flatMap(o=>list(o.ruleIds)).filter(id=>activeRules.get(id)?.protected===true));
  if(protectedRules.length)warnings.push(fault('PROTECTED_BEHAVIOR_REQUIRES_EXISTING_GUARD_APPROVAL',protectedRules.join(',')));
  for(const e of list(r40Report?.errors).slice(0,12)){
    diagnostics.push({kind:'R40_FAILURE',code:e?.code||'UNKNOWN',contractId:null,
      recommendation:'INSPECT_EXISTING_REPORT_AND_REPAIR_IN_ISOLATED_LAB'});
  }
  for(const id of affected){
    if(proven.has(id))continue;
    diagnostics.push({kind:'MISSING_PROOF',contractId:id,
      recommendation:'ADD_OR_EXECUTE_NATIVE_TEST_THROUGH_EXISTING_R39_RUNNER'});
  }
  const recalled=[];
  for(const inc of list(incidents).slice(0,250)){
    if(inc?.verified!==true||!SHA.test(inc.fixCommit||'')||!Array.isArray(inc.changedPaths))continue;
    const related=inc.changedPaths.some(p=>paths.includes(p))||
      list(inc.contractIds).some(id=>affected.has(id));
    if(related)recalled.push({id:inc.id||'incident',fixCommit:inc.fixCommit,
      advisoryOnly:true,recommendation:'REVIEW_VERIFIED_FIX_IN_EXISTING_GIT_HISTORY'});
  }
  const reuseKey=digest({expectedCommit,manifest,changedPaths:paths,ownership,dependencies,
    r40Status:r40Report?.status,r40Target:r40Report?.targetCommit,
    evidence:list(r40Report?.requests)});
  const artifactLinked=artifactReceipt===null?'NOT_SUPPLIED':
    (artifactReceipt.sourceCommit===expectedCommit&&
    /^[a-f0-9]{64}$/i.test(artifactReceipt.apkSha256||'')&&
    /^[a-f0-9]{64}$/i.test(artifactReceipt.certSha256||'')&&
    Number.isSafeInteger(artifactReceipt.versionCode)&&artifactReceipt.versionCode>0)?
    'FIELDS_MATCH_SOURCE_NOT_DEVICE_PROOF':'INVALID_OR_STALE';
  if(artifactLinked==='INVALID_OR_STALE')note('UNTRUSTED_ARTIFACT_RECEIPT');
  let timing={status:'NOT_MEASURED',criticalPathImpact:'NOT_CERTIFIED',baselineP95Ms:null,candidateP95Ms:null};
  if(benchmarks!==null){
    const b=list(benchmarks.baselineMs),c=list(benchmarks.candidateCriticalPathMs);
    const legit=b.length>=7&&b.length===c.length&&
      b.every(x=>Number.isFinite(x)&&x>=0)&&c.every(x=>Number.isFinite(x)&&x>=0)&&
      benchmarks.sameRunner===true&&benchmarks.sameWorkload===true&&benchmarks.r40MandatoryStepsUnchanged===true;
    if(!legit){warnings.push(fault('PERFORMANCE_COMPARISON_UNVERIFIED'));}
    else {
      const x=b.slice().sort((a,b)=>a-b),y=c.slice().sort((a,b)=>a-b);
      const equalOrFaster=median(y)<=median(x)&&p95(y)<=p95(x);
      timing={status:equalOrFaster?'PAIRED_SAMPLES_NO_SLOWDOWN':'REGRESSION_OR_NO_PROOF',
        criticalPathImpact:equalOrFaster?'MEASURED_NON_INCREASE':'NOT_APPROVED',
        baselineP95Ms:p95(x),candidateP95Ms:p95(y),baselineMedianMs:median(x),
        candidateMedianMs:median(y),samples:b.length};
      if(!equalOrFaster)note('TIMING_REGRESSION_BLOCK_PROMOTION');
    }
  }
  if(timing.status!=='PAIRED_SAMPLES_NO_SLOWDOWN')warnings.push(fault('R41_PROMOTION_AWAITS_REAL_TIMING_PROOF'));
  const status=errors.length?'BLOCKED':timing.status!=='PAIRED_SAMPLES_NO_SLOWDOWN'?
    'LAB_ANALYSIS_ONLY':'READY_FOR_EXISTING_GUARD_REVIEW';
  return {schemaVersion:1,feature:'scudo360-evolution-r41',mode:'READ_ONLY_ADVISORY',
    authority:'EXISTING_CHANGE_COMPATIBILITY_GUARD',status,
    sourceCommit:expectedCommit||null,unchangedMandatoryRunner:'R39_AND_R40',
    changedPaths:touches,unknownPaths:unknown,coverage,protectedRules,
    diagnostics:diagnostics.slice(0,30),priorVerifiedIncidents:recalled.slice(0,15),
    reusableEvidenceFingerprint:reuseKey,reusePolicy:'FINGERPRINT_ONLY_NEVER_SKIP_MANDATORY_TEST',
    artifactStatus:artifactLinked,deviceAcceptance:'NOT_CERTIFIED_BY_CI',
    performance:timing,errors,warnings,repairPolicy:'PROPOSE_ISOLATED_LAB_PATCH_ONLY_NEVER_AUTO_MERGE'};
}
