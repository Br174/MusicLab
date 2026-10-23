import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto, randomBytes } from 'node:crypto';
import { createLab46StagingWorker } from './worker-entry.mjs';

if(!globalThis.crypto)globalThis.crypto=webcrypto;
const b64=v=>Buffer.from(v).toString('base64url');
const enc=new TextEncoder();
const URL='https://staging.invalid/api/v1/turbo/decision-sync';
const WRITE_TOKEN='test-only-privileged-server-token-0123456789ABCDE';
const MARKER='musiclab-turbo-lab46-isolated-d1-v1';

const keyPair=await webcrypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},
  true,['sign','verify']);
const publicSpki=new Uint8Array(await webcrypto.subtle.exportKey('spki',keyPair.publicKey));
const keyId=b64(await webcrypto.subtle.digest('SHA-256',publicSpki));
const otherKey=await webcrypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},
  true,['sign','verify']);
const body=JSON.stringify({
 revision:'123e4567-e89b-42d3-a456-426614174000',
 fingerprint:'test|artist|cover|revision',
 payload:{originalTitle:'Song',originalArtist:'Artist',status:'APPROVED',
   candidate:{title:'Song',artist:'Singer',versionFingerprint:'test|artist|cover|revision'}}
});
function environment(overrides={}){
 return {
  LAB46_ISOLATED_STAGING:'1',
  LAB46_ISOLATED_DB_NAME:'musiclab-turbo-lab46-staging',
  LAB46_DEVICE_PUBLIC_SPKI:b64(publicSpki),
  LAB46_WRITE_TOKEN:WRITE_TOKEN,
  DB:{prepare(sql){
    assert.match(sql,/lab46_staging_sentinel/);
    return {bind(marker){return {async first(){
      return marker===MARKER?{marker:MARKER}:null;
    }};}};}
  },...overrides
 };
}
async function signedRequest({
 text=body,timestamp=Math.floor(Date.now()/1000),nonce=b64(randomBytes(16)),
 path='/api/v1/turbo/decision-sync',key=keyPair.privateKey,overrideId=keyId,
}={}){
 const hash=b64(await webcrypto.subtle.digest('SHA-256',enc.encode(text)));
 const canonical='musiclab-lab46-device-v1\nPOST\n/api/v1/turbo/decision-sync\n'+
    timestamp+'\n'+nonce+'\n'+hash;
 const signature=b64(await webcrypto.subtle.sign(
  {name:'ECDSA',hash:'SHA-256'},key,enc.encode(canonical)));
 return new Request('https://staging.invalid'+path,{
  method:'POST',headers:{
   'x-lab46-key-id':overrideId,'x-lab46-timestamp':String(timestamp),
   'x-lab46-nonce':nonce,'x-lab46-signature':signature,
   'content-type':'application/json'
  },body:text
 });
}
function worker(){
 const calls=[];
 const entry=createLab46StagingWorker({
   async fetch(req,env){
     calls.push({
       authorization:req.headers.get('authorization'),
       signature:req.headers.get('x-lab46-signature'),
       body:await req.text(),
       binding:env.LAB46_ISOLATED_DB_NAME
     });
     return new Response('SIGNED_AUTHENTICATED_FORWARD');
   }
 });
 return {entry,calls};
}
test('paired P-256 signature authorizes only idempotent stage sync; privileged bearer stays server-side',async()=>{
 const {entry,calls}=worker();
 const req=await signedRequest();
 assert.equal(req.headers.get('authorization'),null);
 const res=await entry.fetch(req,environment(),{});
 assert.equal(res.status,200);
 assert.equal(await res.text(),'SIGNED_AUTHENTICATED_FORWARD');
 assert.equal(calls.length,1);
 assert.equal(calls[0].authorization,'Bearer '+WRITE_TOKEN);
 assert.equal(calls[0].signature,null);
 assert.equal(calls[0].body,body);
 assert.equal(calls[0].binding,'musiclab-turbo-lab46-staging');
});
test('no pairing configured fails closed before an Android write',async()=>{
 const {entry,calls}=worker();
 const res=await entry.fetch(await signedRequest(),
   environment({LAB46_DEVICE_PUBLIC_SPKI:''}),{});
 assert.equal(res.status,503);assert.equal(calls.length,0);
});
test('a non-enrolled public key cannot impersonate paired Android device',async()=>{
 const {entry,calls}=worker();
 const req=await signedRequest({key:otherKey.privateKey,overrideId:keyId});
 const res=await entry.fetch(req,environment(),{});
 assert.equal(res.status,401);assert.equal(calls.length,0);
});
test('altered decision body after signing fails signature verification',async()=>{
 const {entry,calls}=worker();
 const req=await signedRequest();
 const copy=new Request(req,{body:body.replace('"APPROVED"','"REJECTED"'),duplex:'half'});
 const res=await entry.fetch(copy,environment(),{});
 assert.equal(res.status,401);assert.equal(calls.length,0);
});
test('expired timestamp and impossible future signature rejected',async()=>{
 const {entry,calls}=worker();
 for(const offset of [-130,130]){
  const req=await signedRequest({timestamp:Math.floor(Date.now()/1000)+offset});
  const res=await entry.fetch(req,environment(),{});
  assert.equal(res.status,401);
 }
 assert.equal(calls.length,0);
});
test('invalid nonce, shortened signature and wrong fingerprint denied',async()=>{
 const {entry,calls}=worker();
 for(const [h,value] of [
  ['x-lab46-nonce','bad'],
  ['x-lab46-signature','abc'],
  ['x-lab46-key-id','wrong-key'],
 ]){
  const request=await signedRequest();
  request.headers.set(h,value);
  assert.equal((await entry.fetch(request,environment(),{})).status,401,h);
 }
 assert.equal(calls.length,0);
});
test('only signed decision sync allowed; no private archive or brain write access',async()=>{
 const {entry,calls}=worker();
 for(const route of ['/api/v1/archive/search','/api/v1/brain/decision',
   '/api/v1/discover/expand','/api/v1/memory/discover']){
  const signed=await signedRequest({path:route});
  const res=await entry.fetch(signed,environment(),{});
  assert.equal(res.status,401,route);
 }
 assert.equal(calls.length,0);
});
test('production name, flag, unavailable D1, missing server secret denied',async()=>{
 const {entry,calls}=worker();
 const variants=[
  [{LAB46_ISOLATED_STAGING:'0'},403],
  [{LAB46_ISOLATED_DB_NAME:'musiclab-intelligence-db'},503],
  [{DB:null},503],
  [{LAB46_WRITE_TOKEN:''},503],
 ];
 for(const [env,status] of variants){
  const res=await entry.fetch(await signedRequest(),environment(env),{});
  assert.equal(res.status,status);
 }
 assert.equal(calls.length,0);
});
test('oversized signed payload is refused, no sync route executes',async()=>{
 const {entry,calls}=worker();
 const res=await entry.fetch(await signedRequest({text:'x'.repeat(65537)}),
  environment(),{});
 assert.equal(res.status,413);
 assert.equal(calls.length,0);
});
