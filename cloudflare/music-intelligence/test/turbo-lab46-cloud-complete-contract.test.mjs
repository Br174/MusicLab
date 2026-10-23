import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = file => fs.readFileSync(file, 'utf8');
const registry = JSON.parse(read('behaviors/musiclab-cover.json'));
const migration = read('cloudflare/music-intelligence/migrations/0005_turbo_lab46_complete_archive.sql');
const worker = read('cloudflare/music-intelligence/src/worker-v20.js');
const cloud = read('app/src/main/kotlin/com/metrolist/music/ui/component/CloudMusicDiscovery.kt');
const model = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsVersionSource.kt');
const cache = read('app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt');
const ui = read('app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt');
const fields = ['release_date','country','source_names_json','source_url','credits_detail_json',
  'video_title','recording_fingerprint','legacy_score_20','musiclab_score_100',
  'cover_round','round_position','work_relation_confirmed'];

test('LAB46: one durable owned registry, not an app runtime rule engine',()=>{
 assert.equal(registry.schemaVersion,1);
 assert.equal(registry.project,'musiclab');
 assert.equal(new Set(registry.rules.map(r=>r.id)).size,registry.rules.length);
 for(const r of registry.rules){
  assert.equal(r.owner,'musiclab');assert.equal(r.status,'active');
  assert.equal(r.protected,true);assert.ok(r.acceptance.length);
  assert.ok(r.behavior.length>15);
 }
 assert.ok(registry.rules.length>=10);
});
test('LAB46: D1 schema is additive and preserves existing versions',()=>{
 for(const f of fields) assert.match(migration,new RegExp('ALTER TABLE versions ADD COLUMN '+f+'\\b'));
 assert.doesNotMatch(migration,/DROP TABLE|DELETE FROM|TRUNCATE|ALTER TABLE.*RENAME/i);
});
test('LAB46: Worker returns all available metadata and scores in both archive and memory',()=>{
 for(const f of fields) assert.ok(worker.includes(f),f);
 for(const f of ['releaseDate','sourceNames','musiclabScore100','legacyScore20','coverRound','roundPosition'])
    assert.ok(worker.includes(f),f);
 assert.match(worker,/credits: safeJson\(row\.credits_json\)/);
 assert.match(worker,/creditDetails: Array.isArray\(safeJson\(row\.credits_detail_json\)\)/);
 assert.match(worker,/playbackVideoTitle: row\.video_title/);
});
test('LAB46: Android Cloud parser restores new fields with optional older-record fallbacks',()=>{
 for(const f of ['releaseDate','country','sourceNames','sourceUrl','versionFingerprint',
   'legacyScore20','musiclabScore100','coverRound','roundPosition']){
  assert.ok(cloud.includes('obj.') && cloud.includes('"'+f+'"'),f);
 }
 assert.match(cloud,/internal val turboReadOnly/);
 assert.match(cloud,/if \(turboReadOnly\) return@withContext false/);
});
test('LAB46: local round snapshot keeps new independent fields',()=>{
 for(const f of ['musiclabScore100','coverRound','roundPosition']){
  assert.match(cache,new RegExp('put\\("'+f+'"'));
  assert.match(cache,new RegExp('int\\(json, "'+f+'"'));
 }
 assert.match(cache,/AtomicFile/);
});
test('LAB46: identity and player remain protected, Cloud approvals are not proof',()=>{
 assert.match(ui,/manuallyApproved = false, \/\/ LAB43/);
 assert.match(ui,/workRelationConfirmed = false/);
 assert.match(ui,/seedSourceNamesForCloud\(/);
 assert.match(ui,/CoverSameWorkGate\.hasWorkIdentity\(/);
 assert.match(ui,/TURBO_RESULT_QUOTA = 50/);
 assert.match(model,/val musiclabScore100: Int\? = null/);
});
