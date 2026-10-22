import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import fs from 'node:fs';
import { stagingCloudWriteGate } from '../src/lab46-staging-write-guard.js';
if (!globalThis.crypto) globalThis.crypto = webcrypto;
const token = 'local-test-only-not-a-real-secret-0123456789ABCD';
const good = () => ({
  DB:{prepare(){return {bind(){return {
    async first(){return {marker:'musiclab-turbo-lab46-isolated-d1-v1'};}
  };}};}},
  LAB46_ISOLATED_STAGING:'1',
 LAB46_ISOLATED_DB_NAME:'musiclab-turbo-lab46-staging',LAB46_WRITE_TOKEN:token});
const req = bearer => new Request('https://staging.invalid/api/v1/brain/decision',
 {method:'POST', headers: bearer ? {authorization:'Bearer '+bearer}:{} });
test('default-deny on missing staging explicit flag', async()=>{
 assert.equal((await stagingCloudWriteGate(req(token),{...good(),LAB46_ISOLATED_STAGING:undefined})).status,403);
});
test('no shared/prod DB even with correct token',async()=>{
 const result=await stagingCloudWriteGate(req(token),{...good(),LAB46_ISOLATED_DB_NAME:'musiclab-intelligence-db'});
 assert.equal(result.allowed,false);assert.equal(result.status,503);
});
test('missing database must never grant write',async()=>{
 assert.equal((await stagingCloudWriteGate(req(token),{...good(),DB:null})).allowed,false);
});
test('missing server-side secret is a configuration fault',async()=>{
 assert.equal((await stagingCloudWriteGate(req(token),{...good(),LAB46_WRITE_TOKEN:''})).status,503);
});
test('missing client bearer is unauthorized',async()=>{
 assert.equal((await stagingCloudWriteGate(req(),good())).status,401);
});
test('bad bearer cannot write',async()=>{
 assert.equal((await stagingCloudWriteGate(req('definitely-invalid-000000000000000'),good())).status,401);
});
test('correct staging-only token may pass without claiming actual D1 write',async()=>{
 const result=await stagingCloudWriteGate(req(token),good());
 assert.equal(result.allowed,true);assert.equal(result.reason,'staging_authenticated');
});
test('both existing write routes share same staging gate',()=>{
 const worker=fs.readFileSync('cloudflare/music-intelligence/src/worker-v20.js','utf8');
 assert.match(worker,/url\.pathname === '\/api\/v1\/brain\/decision'/);
 assert.match(worker,/url\.pathname === '\/api\/v1\/playback\/binding'/);
 assert.match(worker,/stagingCloudWriteGate\(request, env\)/);
 assert.match(worker,/env\?\.LAB46_ISOLATED_STAGING === '1'/);
});
test('Android Turbo still refuses any shared production Cloud write',()=>{
 const code=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt','utf8');
 assert.match(code,/if \(turboReadOnly\) return@withContext false/);
});
test('offline decisions are queued atomically without fake remote success',()=>{
 const code=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverCloudPendingOutbox.kt','utf8');
 assert.match(code,/outbox\.startWrite\(\)/);
 assert.match(code,/outbox\.finishWrite\(stream\)/);
 assert.match(code,/old\.optString\("fingerprint"\) != seed\.fingerprint/);
 assert.match(code,/No clear-on-attempt/);
});

test('actual staging D1 sentinel is mandatory, not just a declared DB name',async()=>{
 const noSentinel = { ...good(), DB:{prepare(){return {bind(){return {
   async first(){return null;}
 };}};}} };
 const result = await stagingCloudWriteGate(req(token),noSentinel);
 assert.equal(result.allowed,false);
 assert.equal(result.reason,'staging_db_sentinel_missing');
});
test('a database without sentinel table must fail closed',async()=>{
 const missing = {...good(), DB:{prepare(){throw new Error('no such table');}}};
 const result = await stagingCloudWriteGate(req(token),missing);
 assert.equal(result.allowed,false);
 assert.equal(result.status,503);
});
test('isolated Wrangler template cannot accidentally bind shared production D1',()=>{
 const config=fs.readFileSync('cloudflare/music-intelligence/wrangler.lab46-staging.template.jsonc','utf8');
 const bootstrap=fs.readFileSync('cloudflare/music-intelligence/lab46-staging-only/bootstrap.sql','utf8');
 assert.match(config,/"database_name": "musiclab-turbo-lab46-staging"/);
 assert.doesNotMatch(config,/7f36c6a5-5361-47cf-8520-b2ba398ea731/);
 assert.match(bootstrap,/CREATE TABLE IF NOT EXISTS lab46_staging_sentinel/);
 assert.match(bootstrap,/musiclab-turbo-lab46-isolated-d1-v1/);
});
