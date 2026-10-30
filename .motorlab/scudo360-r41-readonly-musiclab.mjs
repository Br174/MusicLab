/**
 * MusicLab r41 opt-in read-only adapter, NOT part of Android/runtime/build.
 * This only consumes a pre-existing Scudo360 r40 quality JSON: no test runs.
 * Invocation (optional, never automatic):
 * node .motorlab/scudo360-r41-readonly-musiclab.mjs --source-commit=<sha> --changed-path=<known-test-path>
 */
import fs from 'node:fs';
import {assessEvolutionR41} from './regression/scudo360-evolution-r41.mjs';

const getOption=(key)=>process.argv.slice(2).filter(arg=>arg.startsWith('--'+key+'='))
  .map(arg=>arg.slice(key.length+3));
const expectedCommit=getOption('source-commit')[0];
const changedPaths=getOption('changed-path');
const files={
  manifest:'.motorlab/scudo360-contracts.json',
  quality:'.motorlab/scudo360-r40-quality.json',
  adoption:'.motorlab/scudo360-r41-adoption.json'
};
try {
  if(!expectedCommit||changedPaths.length===0){
    console.log(JSON.stringify({mode:'READ_ONLY_ADVISORY_OPT_IN',status:'MISSING_EXPLICIT_SOURCE_OR_CHANGE_PATHS',
      note:'No native tests, APK build, promotion or Android runtime changes performed.'}));
    process.exitCode=2;
  }else{
    for(const [label,path] of Object.entries(files)){
      if(!fs.existsSync(path))throw new Error('EXISTING_EVIDENCE_MISSING:'+label);
    }
    const read=(p)=>JSON.parse(fs.readFileSync(p,'utf8'));
    const policy=read(files.adoption);
    if(policy.mode!=='READ_ONLY_ADVISORY_OPT_IN'||policy.activation!=='EXPLICIT_ONLY_NO_APK_OR_WORKFLOW_HOOK')
      throw new Error('INVALID_OPT_IN_POLICY');
    const result=assessEvolutionR41({
      expectedCommit,manifest:read(files.manifest),r40Report:read(files.quality),
      changedPaths,ownership:policy.ownership,dependencies:policy.dependencies
    });
    console.log(JSON.stringify(result,null,2));
    if(result.status==='BLOCKED')process.exitCode=1;
  }
}catch(error){
  console.error('SCUDO360_R41_READ_ONLY_PENDING '+String(error.message||error));
  process.exitCode=2;
}
