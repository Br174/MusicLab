import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createLab46StagingWorker } from './worker-entry.mjs';

if (!globalThis.crypto) globalThis.crypto=webcrypto;

const TEST_TOKEN='mock-only-credential-not-to-be-deployed-abcdef1234567890';
const STAGING_DB='musiclab-turbo-lab46-staging';
const STAGING_UUID='e66bd500-cdaa-453c-a81c-c624a9d2611e';
const PRODUCTION_UUID='7f36c6a5-5361-47cf-8520-b2ba398ea731';
const PROD_WORKER_NAME='musiclab-music-intelligence';
const MARKER='musiclab-turbo-lab46-isolated-d1-v1';
const config=JSON.parse(readFileSync(
  'cloudflare/music-intelligence/lab46-staging-only/wrangler.staging.verified.jsonc','utf8'
));
const prod=JSON.parse(readFileSync('cloudflare/music-intelligence/wrangler.jsonc','utf8'));
const verifiedDb=(marker=MARKER)=>({
  prepare(sql) {
    assert.match(sql,/SELECT marker FROM lab46_staging_sentinel WHERE marker=\?1 LIMIT 1/);
    return {bind(requested) {
      return {async first(){return requested===marker?{marker}:null;}};
    }};
  }
});
const env=()=>({
  LAB46_ISOLATED_STAGING:'1', LAB46_ISOLATED_DB_NAME:STAGING_DB,
  LAB46_WRITE_TOKEN:TEST_TOKEN, DB:verifiedDb(),
});
const req=(path='/api/v1/archive/search',bearer=TEST_TOKEN,method='POST')=>
  new Request('https://staging.invalid'+path,{
    method,headers:bearer?{authorization:'Bearer '+bearer}:{},
    ...(method==='GET'?{}:{body:'{}'})
  });
function mock(){
  const calls=[];
  const worker=createLab46StagingWorker({
    async fetch(request,binding) {
      calls.push({method:request.method,path:new URL(request.url).pathname,binding});
      return new Response('SAFE_MOCK_FORWARD');
    }
  });
  return {worker,calls};
}
function validateConfig(c){
  assert.equal(c.name,'musiclab-turbo-lab46-isolated-staging');
  assert.notEqual(c.name,PROD_WORKER_NAME);
  assert.equal(c.main,'worker-entry.mjs');
  assert.equal(c.workers_dev,false,'public workers.dev endpoint must stay off');
  assert.ok(!c.routes && !c.route && !c.triggers,'no custom deployment routes');
  assert.equal(c.vars?.LAB46_ISOLATED_STAGING,'1');
  assert.equal(c.vars?.LAB46_ISOLATED_DB_NAME,STAGING_DB);
  assert.equal(c.d1_databases?.length,1);
  assert.equal(c.d1_databases[0].binding,'DB');
  assert.equal(c.d1_databases[0].database_name,STAGING_DB);
  assert.equal(c.d1_databases[0].database_id,STAGING_UUID);
  assert.notEqual(c.d1_databases[0].database_id,PRODUCTION_UUID);
  assert.equal(prod.d1_databases[0].database_id,PRODUCTION_UUID);
  assert.deepEqual(c.secrets?.required,['LAB46_WRITE_TOKEN']);
  assert.ok(!('LAB46_WRITE_TOKEN' in c.vars),'no bearer token in tracked vars');
  assert.ok(!('GEMINI_API_KEY' in c.vars),'no billable Gemini key');
  assert.ok(!('CLOUDFLARE_API_TOKEN' in c.vars),'no account control-plane credential');
}

test('pinned staging config matches D1, secret requirements and non-public network',()=>{
  validateConfig(config);
});
test('wrong DB, public route, plaintext token or missing secret rejected by config',()=>{
  const bad=[
    c=>{c.d1_databases[0].database_id=PRODUCTION_UUID;},
    c=>{c.workers_dev=true;},
    c=>{c.vars.LAB46_WRITE_TOKEN=TEST_TOKEN;},
    c=>{c.secrets.required=[];},
    c=>{c.routes=[{pattern:'*',zone_name:'example.test'}];},
    c=>{c.vars.LAB46_ISOLATED_STAGING='0';},
    c=>{c.d1_databases.push({...c.d1_databases[0]});}
  ];
  for(const breakIt of bad) {
    const c=structuredClone(config);breakIt(c);
    assert.throws(()=>validateConfig(c));
  }
});
test('blocks unknown public/legacy routes before auth or handler invocation',async()=>{
  const {worker,calls}=mock();
  for(const path of ['/','/api/v1/admin','/api/v1/technical-destination',
    '/api/v1/playback-binding','/api/v1/nope']) {
    const res=await worker.fetch(req(path),env(),{});
    assert.equal(res.status,404,path);
  }
  assert.equal(calls.length,0);
});
test('blocks GET, DELETE and OPTIONS including read endpoints',async()=>{
  const {worker,calls}=mock();
  for(const method of ['GET','DELETE','OPTIONS']){
    const res=await worker.fetch(req('/api/v1/archive/search',TEST_TOKEN,method),env(),{});
    assert.equal(res.status,405,method);
  }
  assert.equal(calls.length,0);
});
test('missing, short and wrong bearer fail before reaching memory/archive read',async()=>{
  const {worker,calls}=mock();
  for(const bearer of [null,'invalid','incorrect-credential-at-least-32-bytes']){
    const res=await worker.fetch(req('/api/v1/archive/search',bearer),env(),{});
    assert.equal(res.status,401,String(bearer));
    assert.equal(res.headers.get('cache-control'),'no-store');
    assert.equal(res.headers.get('access-control-allow-origin'),null);
    assert.doesNotMatch(await res.text(),/mock-only-credential/);
  }
  assert.equal(calls.length,0);
});
test('no staging mode, wrong DB, missing binding, missing secret or missing sentinel fail closed',async()=>{
  const {worker,calls}=mock();
  const bad=[
    [{...env(),LAB46_ISOLATED_STAGING:'0'},403],
    [{...env(),LAB46_ISOLATED_DB_NAME:'musiclab-intelligence-db'},503],
    [{...env(),DB:null},503],
    [{...env(),LAB46_WRITE_TOKEN:''},503],
    [{...env(),DB:verifiedDb('production-or-impostor')},503],
  ];
  for(const [binding,status] of bad){
    const res=await worker.fetch(req(),binding,{});
    assert.equal(res.status,status);
  }
  assert.equal(calls.length,0);
});
test('authorized staging requests alone reach delegate, including read and sync',async()=>{
  const {worker,calls}=mock();
  const paths=[
    '/api/v1/archive/search','/api/v1/memory/discover',
    '/api/v1/turbo/decision-sync','/api/v1/brain/decision',
    '/api/v1/playback/binding','/api/v1/brain/plan',
    '/api/v1/discover/initial','/api/v1/discover/expand'
  ];
  for(const path of paths){
    const result=await worker.fetch(req(path),env(),{});
    assert.equal(result.status,200,path);
    assert.equal(await result.text(),'SAFE_MOCK_FORWARD');
  }
  assert.equal(calls.length,paths.length);
  assert.deepEqual(calls.map(c=>c.path),paths);
});
