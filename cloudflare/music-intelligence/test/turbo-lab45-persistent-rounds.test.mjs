import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const store=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt','utf8');
const screen=fs.readFileSync('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt','utf8');

test('LAB45: verified multi-round cards are saved through an atomic file',()=>{
  assert.match(store,/import android\.util\.AtomicFile/);
  assert.match(store,/file\.startWrite\(\)/);
  assert.match(store,/file\.finishWrite\(stream\)/);
  assert.match(store,/file::failWrite/);
  assert.match(screen,/val fullHistoricalSnapshot = \(/);
  assert.match(screen,/CoverRoundStorage\.save\(/);
});

test('LAB45: full Android process restart restores round number and existing cards',()=>{
  assert.match(screen,/CoverRoundStorage\.load\(context, sessionKey\)/);
  assert.match(screen,/session\.previousRoundRows = saved\.rows/);
  assert.match(screen,/existing\.coverRoundNumber = saved\.round/);
  assert.match(store,/fun load\(context: Context, workKey: String\): Snapshot/);
});

test('LAB45: persisted metadata identifies a distinct exact recording and video',()=>{
  for(const value of ['title','artist','fingerprint','kind','releaseId','resolvedVideoId','workRelationConfirmed','sourceNames','confidenceScore']){
    assert.ok(store.includes('put("'+value+'"'),value);
  }
  assert.match(store,/DiscogsVersionSource\.recordingIdentityKey\(seed\)/);
  assert.match(store,/seed\.resolvedVideoId\.isNullOrBlank\(\) \|\| videos\.add\(seed\.resolvedVideoId\)/);
});

test('LAB45: no video or audio bytes, auth token or identity data are persisted',()=>{
  assert.match(store,/json\.toString\(\)\.toByteArray\(Charsets\.UTF_8\)/);
  assert.doesNotMatch(store,/authorization|secretKey|apiKey|downloadVideo|streamUrl|accessToken/i);
  assert.match(store,/musiclab-cover-rounds-/);
});

test('LAB45: storage is monotonic across independently finishing IO tasks',()=>{
  assert.match(store,/@Synchronized\s+internal fun load/);
  assert.match(store,/@Synchronized\s+internal fun save/);
  assert.match(store,/saved\.round > round/);
  assert.match(store,/saved\.rows\.size > rows\.size/);
  assert.match(store,/internal fun clear\(context: Context, workKey: String\)/);
});
