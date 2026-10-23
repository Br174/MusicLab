import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { decisionIdentityKeys, decisionRecordingVersionKey } from '../src/worker-v20.js';

const create=(fingerprint,reason='turbo_lab46_pending')=>({
 originalTitle:'Il mondo',
 originalArtist:'Jimmy Fontana',
 reason,
 candidate:{
  title:'Il mondo',artist:'Cantante giovane',category:'cover',
  language:'it',versionFingerprint:fingerprint,
 },
});

test('LAB46: same artist/song but different recordings never share a new archive key',()=>{
 const first=decisionRecordingVersionKey(create('live-cinema-2025'));
 const second=decisionRecordingVersionKey(create('live-theatre-2025'));
 assert.notEqual(first,second);
 assert.match(first,/recording:live-cinema-2025/);
 assert.match(second,/recording:live-theatre-2025/);
});
test('LAB46: same recording has deterministic key across retry',()=>{
 assert.equal(decisionRecordingVersionKey(create('live-cinema-2025')),
   decisionRecordingVersionKey(create('live-cinema-2025')));
});
test('LAB46: preexisting LAB38/39 Cloud persistence semantics remain unchanged',()=>{
 const legacy=create('live-cinema-2025','manual_android_lab39');
 assert.equal(decisionRecordingVersionKey(legacy),decisionIdentityKeys(legacy).versionKey);
 const blank=create('', 'turbo_lab46_pending');
 assert.equal(decisionRecordingVersionKey(blank),decisionIdentityKeys(blank).versionKey);
});
test('LAB46: current stage preserves fingerprint and video-based DB lookup before old category title',()=>{
 const source=fs.readFileSync(
   'cloudflare/music-intelligence/src/worker-v20.js','utf8');
 assert.match(source,/recording_fingerprint=\?2/);
 assert.match(source,/JOIN playback_bindings pb ON pb\.version_id=v\.id/);
 assert.match(source,/versionKey = decisionRecordingVersionKey\(input\)/);
});
