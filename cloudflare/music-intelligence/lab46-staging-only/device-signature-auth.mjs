/**
 * LAB46 owner-paired Android Keystore P-256 public-key authentication.
 * The owner's public SPKI key is provisioned to the STAGING Worker separately
 * after out-of-band fingerprint verification. Never accept public keys from
 * untrusted requests, and never store privileged tokens in the Android APK.
 *
 * Only the existing idempotent /turbo/decision-sync operation is authorized
 * for device signatures; other routes keep the privileged operator-only gate.
 * Receipt revisions provide durable replay/idempotency control for that route.
 * No Cloudflare mutation or deployment occurs in this module.
 */
const enc=new TextEncoder();
const DEVICE_PATH='/api/v1/turbo/decision-sync';
const MAX_BODY_BYTES=65536;
const MAX_AGE_SEC=120;
const headers=Object.freeze({
  id:'x-lab46-key-id',
  timestamp:'x-lab46-timestamp',
  nonce:'x-lab46-nonce',
  signature:'x-lab46-signature',
});
const b64url=(bytes)=>btoa(String.fromCharCode(...bytes))
  .replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
function fromB64url(value,min,max){
  if(typeof value!=='string'||value.length<min||value.length>max||
      !/^[A-Za-z0-9_-]+$/.test(value))throw Error('bad_base64url');
  const base=value.replace(/-/g,'+').replace(/_/g,'/');
  const bin=atob(base+'='.repeat((4-base.length%4)%4));
  return Uint8Array.from(bin,c=>c.charCodeAt(0));
}
async function digest(buffer){
  return new Uint8Array(await crypto.subtle.digest('SHA-256',buffer));
}
const decline=(reason,status=401)=>({allowed:false,reason,status});
export async function verifyDeviceSignature(request,env,nowMs=Date.now()){
  if(request.method!=='POST')return decline('method_denied',405);
  const url=new URL(request.url);
  if(url.pathname!==DEVICE_PATH||url.search||url.protocol!=='https:')
    return decline('path_denied',403);
  if(env?.LAB46_ISOLATED_STAGING!=='1'||
    env?.LAB46_ISOLATED_DB_NAME!=='musiclab-turbo-lab46-staging'||!env?.DB)
    return decline('staging_binding_not_ready',503);
  const spkiString=env?.LAB46_DEVICE_PUBLIC_SPKI;
  if(typeof spkiString!=='string'||!spkiString)
    return decline('device_enrollment_required',503);
  let spki,signature;
  try{
    spki=fromB64url(spkiString,90,240);
    signature=fromB64url(request.headers.get(headers.signature),86,86);
    if(signature.length!==64)return decline('signature_bad_size');
  }catch{
    return decline('signature_or_key_invalid');
  }
  const identity=b64url(await digest(spki));
  const keyId=request.headers.get(headers.id)||'';
  if(identity!==keyId)return decline('device_key_not_enrolled');
  const timestamp=request.headers.get(headers.timestamp)||'';
  if(!/^[1-9][0-9]{9}$/.test(timestamp))
    return decline('timestamp_invalid');
  const age=Math.abs(Math.floor(nowMs/1000)-Number(timestamp));
  if(age>MAX_AGE_SEC)return decline('signature_expired');
  const nonce=request.headers.get(headers.nonce)||'';
  try{
    if(fromB64url(nonce,22,22).length!==16) return decline('nonce_invalid');
  }catch{return decline('nonce_invalid');}
  const declaredLength=Number(request.headers.get('content-length')||0);
  if(!Number.isFinite(declaredLength)||declaredLength>MAX_BODY_BYTES)
    return decline('body_too_large',413);
  let body;
  try{
    body=await request.clone().arrayBuffer();
  }catch{return decline('body_unreadable',400);}
  if(body.byteLength===0||body.byteLength>MAX_BODY_BYTES)
    return decline('body_too_large',413);
  const bodyHash=b64url(await digest(body));
  const canonical='musiclab-lab46-device-v1\nPOST\n'+DEVICE_PATH+'\n'+
    timestamp+'\n'+nonce+'\n'+bodyHash;
  try{
    const key=await crypto.subtle.importKey('spki',spki,
      {name:'ECDSA',namedCurve:'P-256'},false,['verify']);
    const valid=await crypto.subtle.verify(
      {name:'ECDSA',hash:'SHA-256'},key,signature,enc.encode(canonical));
    if(!valid)return decline('signature_mismatch');
  }catch{return decline('signature_mismatch');}
  return {allowed:true,status:200,reason:'verified_owner_paired_device',keyId};
}
export function hasDeviceSignatureHeaders(request){
  return Object.values(headers).some(k=>request.headers.has(k));
}
export { DEVICE_PATH };
