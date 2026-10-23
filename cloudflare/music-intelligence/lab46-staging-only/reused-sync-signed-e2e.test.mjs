/**
 * LAB46 integration proof: REUSES existing Cloud sync receipt coordinator,
 * D1 staging-only write guard, Android P-256 wire contract and Worker entry.
 * No new endpoint, alternate sync algorithm, auth mechanism, remote requests,
 * real DB writes, external services or production changes.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto, randomBytes } from 'node:crypto';
import { createLab46StagingWorker } from './worker-entry.mjs';
import { syncLab46Decision } from '../src/lab46-staging-sync.js';

if (!globalThis.crypto) globalThis.crypto=webcrypto;
const e=new TextEncoder();
const b64=bytes=>Buffer.from(bytes).toString('base64url');
const MARKER='musiclab-turbo-lab46-isolated-d1-v1';
const privileged='server-only-test-token-0123456789ABCDEFGHIJKLMNOP';
const fp='discogs|il-mondo|cover|id42';
const revision='123e4567-e89b-42d3-a456-426614174000';
const baseDecision=()=>({
 revision,fingerprint:fp,
 payload:{originalTitle:'Il mondo',originalArtist:'Jimmy Fontana',status:'APPROVED',
 candidate:{title:'Il mondo',artist:'Another artist',category:'cover',
 versionFingerprint:fp,coverRound:2,roundPosition:74,musiclabScore100:84}}
});
const deviceKeys=await webcrypto.subtle.generateKey(
 {name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
const spki=new Uint8Array(await webcrypto.subtle.exportKey('spki',deviceKeys.publicKey));
const keyId=b64(await webcrypto.subtle.digest('SHA-256',spki));
function memoryDb(){
 const receipts=new Map();
 const queries=[];
 return {
  receipts,queries,
  prepare(sql){
   queries.push(sql);
   return {
    bind(...args){return {
     async first(){
      if(sql.includes('lab46_staging_sentinel'))
       return args[0]===MARKER?{marker:MARKER}:null;
      if(sql.includes('SELECT revision,fingerprint,payload_hash,state,version_id'))
       return receipts.get(args[0])||null;
      throw Error('unexpected_first_SQL');
     },
     async run(){
      if(sql.includes('INSERT OR IGNORE INTO lab46_sync_receipts')){
       if(receipts.has(args[0]))return {meta:{changes:0}};
       receipts.set(args[0],{revision:args[0],fingerprint:args[1],
         payload_hash:args[2],state:'pending',version_id:null});
       return {meta:{changes:1}};
      }
      if(sql.includes("SET state='committed'")){
       const row=receipts.get(args[1]);
       if(!row||row.state!=='pending')return {meta:{changes:0}};
       row.state='committed';row.version_id=args[0];return {meta:{changes:1}};
      }
      if(sql.includes("SET state='failed'")){
       const row=receipts.get(args[0]);
       if(!row||row.state!=='pending')return {meta:{changes:0}};
       row.state='failed';return {meta:{changes:1}};
      }
      if(sql.includes("SET state='pending'")){
       const row=receipts.get(args[0]);
       if(!row||row.state!=='failed'||row.payload_hash!==args[1])
        return {meta:{changes:0}};
       row.state='pending';return {meta:{changes:1}};
      }
      throw Error('unexpected_write_SQL');
     }
    };}
   };
  }
 };
}
function env(db,overrides={}){
 return {
   DB:db, LAB46_ISOLATED_STAGING:'1',
   LAB46_ISOLATED_DB_NAME:'musiclab-turbo-lab46-staging',
   LAB46_DEVICE_PUBLIC_SPKI:b64(spki),
   LAB46_WRITE_TOKEN:privileged,
   ...overrides
 };
}
async function signed(body=baseDecision(),options={}){
 const text=JSON.stringify(body);
 const timestamp=options.timestamp??Math.floor(Date.now()/1000);
 const nonce=options.nonce??b64(randomBytes(16));
 const digest=b64(await webcrypto.subtle.digest('SHA-256',e.encode(text)));
 const canonical='musiclab-lab46-device-v1\nPOST\n/api/v1/turbo/decision-sync\n'+
   timestamp+'\n'+nonce+'\n'+digest;
 const signature=b64(await webcrypto.subtle.sign(
   {name:'ECDSA',hash:'SHA-256'},deviceKeys.privateKey,e.encode(canonical)));
 return new Request('https://staging.invalid'+
   (options.path??'/api/v1/turbo/decision-sync'),{
    method:'POST',headers:{
      'content-type':'application/json',
      'x-lab46-key-id':keyId,'x-lab46-timestamp':String(timestamp),
      'x-lab46-nonce':nonce,'x-lab46-signature':signature,
    },body:text
   });
}
function harness(options={}){
 const db=memoryDb();
 let persistCount=0;
 let forwardedAuth=null;
 const delegate={
  async fetch(req,binding){
    forwardedAuth=req.headers.get('authorization');
    assert.equal(binding.DB,db);
    return syncLab46Decision(req,db,async (_db,_payload)=>{
      persistCount++;
      return {httpStatus:200,body:{stato:'salvato',versionId:'version-verified-1'}};
    });
  }
 };
 const worker=createLab46StagingWorker(delegate);
 const config=env(db,options);
 return {db,worker,config,get persistCount(){return persistCount;},
   get forwardedAuth(){return forwardedAuth;}};
}
test('reuses genuine receipt coordinator through signed staging Worker: 1 save, 1 idempotent replay',async()=>{
 const h=harness();
 const firstRequest=await signed();
 assert.equal(firstRequest.headers.has('authorization'),false);
 const first=await h.worker.fetch(firstRequest,h.config,{});
 assert.equal(first.status,200);
 const receipt=await first.json();
 assert.equal(receipt.receipt,'staging_verified_committed');
 assert.equal(receipt.revision,revision);
 assert.equal(receipt.fingerprint,fp);
 assert.equal(receipt.replayed,false);
 assert.equal(h.forwardedAuth,'Bearer '+privileged);
 assert.equal(h.db.receipts.get(revision).state,'committed');
 const replay=await h.worker.fetch(await signed(),h.config,{});
 assert.equal(replay.status,200);
 assert.equal((await replay.json()).replayed,true);
 assert.equal(h.persistCount,1,'never duplicate an already committed editorial write');
 assert.equal(h.db.receipts.size,1);
});
test('correctly re-signed changed decision with same revision yields 409, never overwrites receipt',async()=>{
 const h=harness();
 assert.equal((await h.worker.fetch(await signed(),h.config,{})).status,200);
 const changed=baseDecision();changed.payload.status='REJECTED';
 const rejected=await h.worker.fetch(await signed(changed),h.config,{});
 assert.equal(rejected.status,409);
 assert.equal((await rejected.json()).reason,'revision_payload_mismatch');
 assert.equal(h.persistCount,1);
 assert.equal(h.db.receipts.get(revision).state,'committed');
});
test('without owner-paired SPKI no signature can create a receipt or persist a decision',async()=>{
 const h=harness({LAB46_DEVICE_PUBLIC_SPKI:''});
 const response=await h.worker.fetch(await signed(),h.config,{});
 assert.equal(response.status,503);
 assert.equal(h.persistCount,0);assert.equal(h.db.receipts.size,0);
});
test('altered body without new Keystore signature fails before the real sync handler',async()=>{
 const h=harness();
 const valid=await signed();
 const altered=new Request(valid,{body:JSON.stringify({
  ...baseDecision(),payload:{...baseDecision().payload,status:'REJECTED'}
 }),duplex:'half'});
 const response=await h.worker.fetch(altered,h.config,{});
 assert.equal(response.status,401);
 assert.equal(h.persistCount,0);assert.equal(h.db.receipts.size,0);
});
test('stale signature and signed request to unrelated inherited archive cannot mutate D1',async()=>{
 const h=harness();
 const old=await signed(baseDecision(),{timestamp:Math.floor(Date.now()/1000)-180});
 assert.equal((await h.worker.fetch(old,h.config,{})).status,401);
 const wrongRoute=await signed(baseDecision(),{path:'/api/v1/archive/search'});
 assert.equal((await h.worker.fetch(wrongRoute,h.config,{})).status,401);
 assert.equal(h.persistCount,0);assert.equal(h.db.receipts.size,0);
});
test('pending/uncertain remote receipt preserves pending Android revision, not fake success',async()=>{
 const h=harness();
 assert.equal((await h.worker.fetch(await signed(),h.config,{})).status,200);
 h.db.receipts.get(revision).state='pending';
 const res=await h.worker.fetch(await signed(),h.config,{});
 assert.equal(res.status,409);
 assert.equal((await res.json()).reason,'pending_requires_recovery');
 assert.equal(h.persistCount,1);
});
