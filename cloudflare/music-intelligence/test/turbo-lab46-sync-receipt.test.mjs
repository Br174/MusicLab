import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { syncLab46Decision } from '../src/lab46-staging-sync.js';

if (!globalThis.crypto) globalThis.crypto = webcrypto;

const revision='123e4567-e89b-42d3-a456-426614174000';
const fingerprint='discogs|cover|live|42';
const payload=()=>({
 originalTitle:'Il mondo',originalArtist:'Jimmy Fontana',status:'APPROVED',
 candidate:{title:'Il mondo',artist:'Nuova cantante',category:'live',
   versionFingerprint:fingerprint,playbackVideoId:'AbcdEFG1234',
   releaseDate:'2024-05-18',coverUrl:'https://images.test/cover.jpg',
   musiclabScore100:84,legacyScore20:16,coverRound:2,roundPosition:74,
   credits:{songwriters:['Gianni Meccia']},
   creditDetails:[{name:'Tecnico',role:'Sound engineer'}]},
});
const body=(changes={})=>({
 revision,fingerprint,payload:payload(),...changes,
});
const req=b=>new Request('https://staging.invalid/api/v1/turbo/decision-sync',
 {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)});
function mockDb(){
 const store=new Map();
 return {
  store,
  prepare(sql){return {
    bind(...args){return {
      async first(){if(sql.includes('SELECT revision'))return store.get(args[0])||null;
         throw new Error('unexpected first: '+sql);},
      async run(){
        if(sql.includes('INSERT OR IGNORE INTO lab46_sync_receipts')){
          if(store.has(args[0]))return {meta:{changes:0}};
          store.set(args[0],{revision:args[0],fingerprint:args[1],
            payload_hash:args[2],state:'pending',version_id:null});
          return {meta:{changes:1}};
        }
        if(sql.includes("SET state='pending'")){
          const row=store.get(args[0]);
          if(!row || row.state!=='failed' || row.payload_hash!==args[1])return {meta:{changes:0}};
          row.state='pending';return {meta:{changes:1}};
        }
        if(sql.includes("SET state='failed'")){
          const row=store.get(args[0]);
          if(!row || row.state!=='pending')return {meta:{changes:0}};
          row.state='failed';return {meta:{changes:1}};
        }
        if(sql.includes("SET state='committed'")){
          const row=store.get(args[1]);
          if(!row || row.state!=='pending')return {meta:{changes:0}};
          row.state='committed';row.version_id=args[0];return {meta:{changes:1}};
        }
        throw new Error('unexpected run: '+sql);
      }
    };},
  };}
 };
}
const success=async()=>({httpStatus:200,body:{stato:'salvato',versionId:'version-2'}});
test('LAB46: one authenticated staging revision gets exact verified commit receipt',async()=>{
 const db=mockDb();
 const res=await syncLab46Decision(req(body()),db,success);
 assert.equal(res.status,200);
 const result=await res.json();
 assert.equal(result.stato,'salvato');
 assert.equal(result.revision,revision);
 assert.equal(result.fingerprint,fingerprint);
 assert.equal(result.receipt,'staging_verified_committed');
 assert.equal(result.replayed,false);
 assert.equal(db.store.get(revision).state,'committed');
});
test('LAB46: identical retry reuses receipt without another D1 editorial mutation',async()=>{
 const db=mockDb();let writes=0;
 const save=async()=>{writes++;return success();};
 const first=await syncLab46Decision(req(body()),db,save);
 const second=await syncLab46Decision(req(body()),db,save);
 assert.equal(first.status,200);assert.equal(second.status,200);
 assert.equal((await second.json()).replayed,true);
 assert.equal(writes,1);
});
test('LAB46: same revision with altered musical decision cannot overwrite saved proof',async()=>{
 const db=mockDb();let writes=0;
 const save=async()=>{writes++;return success();};
 await syncLab46Decision(req(body()),db,save);
 const tampered=body();tampered.payload.status='REJECTED';
 const res=await syncLab46Decision(req(tampered),db,save);
 assert.equal(res.status,409);
 assert.equal(writes,1);
});
test('LAB46: unknown pending receipt requires explicit recovery rather than replay',async()=>{
 const db=mockDb();let writes=0;
 const save=async()=>{writes++;return success();};
 await syncLab46Decision(req(body()),db,save);
 db.store.get(revision).state='pending';
 const res=await syncLab46Decision(req(body()),db,save);
 assert.equal(res.status,409);assert.equal(writes,1);
 assert.equal((await res.json()).reason,'pending_requires_recovery');
});
test('LAB46: failed save does not produce a success receipt; later retry is explicit',async()=>{
 const db=mockDb();
 const bad=async()=>({httpStatus:500,body:{errore:'offline'}});
 const first=await syncLab46Decision(req(body()),db,bad);
 assert.equal(first.status,502);assert.equal(db.store.get(revision).state,'failed');
 const retry=await syncLab46Decision(req(body()),db,success);
 assert.equal(retry.status,200);
 assert.equal(db.store.get(revision).state,'committed');
});
test('LAB46: metadata collision, nonuuid revision and invalid playback ID rejected',async()=>{
 const db=mockDb();let writes=0;
 const save=async()=>{writes++;return success();};
 const b=body({revision:'not-a-uuid'});
 assert.equal((await syncLab46Decision(req(b),db,save)).status,422);
 const mismatch=body({fingerprint:'different-recording'});
 assert.equal((await syncLab46Decision(req(mismatch),db,save)).status,422);
 const badId=body();badId.payload.candidate.playbackVideoId='notvalid';
 assert.equal((await syncLab46Decision(req(badId),db,save)).status,422);
 assert.equal(writes,0);assert.equal(db.store.size,0);
});
test('LAB46: missing D1 receipt ledger cannot silently send or acknowledge',async()=>{
 const db={prepare(){throw new Error('no such table: lab46_sync_receipts');}};
 const res=await syncLab46Decision(req(body()),db,success);
 assert.equal(res.status,503);assert.equal((await res.json()).reason,'receipt_table_missing');
});
