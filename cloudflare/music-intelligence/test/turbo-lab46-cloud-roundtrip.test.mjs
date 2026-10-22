import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker-v20.js';

const work = {
  id: 'work-1', search_key: 'il mondo|jimmy fontana',
  canonical_title: 'Il mondo', original_artist: 'Jimmy Fontana',
  original_year: 1965, original_language: 'it',
  credits_json: '{}', resolver_version: 12,
};
const sample = {
  id: 'version-2', work_id: work.id, canonical_title: 'Il mondo',
  canonical_artist: 'Cantante giovane', category: 'cover', language: 'it',
  year: 2024, album: 'Serata dal vivo',
  cover_url: 'https://example.test/poster.jpg',
  credits_detail_json: JSON.stringify([
    { name:'Cantante giovane', role:'Lead vocals' },
    { name:'Fonico', role:'Sound engineer' },
  ]),
  credits_json: JSON.stringify({
    songwriters: ['Gianni Meccia'],
    composers: ['Jimmy Fontana', 'Carlo Pes'],
    lyricists: ['Gianni Meccia'],
    producers: ['Produttore'],
    label: 'Piccola etichetta',
  }),
  release_date: '2024-05-18', country: 'IT',
  source_names_json: JSON.stringify(['MusicBrainz', 'Discogs']),
  source_url: 'https://example.test/source',
  recording_fingerprint: 'cover-live-42',
  legacy_score_20: 16, musiclab_score_100: 81,
  cover_round: 2, round_position: 74,
  work_relation_confirmed: 0,
  video_title: 'Il mondo (live)',
  playback_video_id: 'ABcdef12345',
  playback_video_source: 'youtube_verified',
  same_work_score: 84, version_type_score: 90,
  user_verified: 1, user_rejected: 0,
  decision_status: 'APPROVED',
};

function dbForRead(row = sample) {
  const queries = [];
  return {
    queries,
    prepare(sql) {
      return {
        bind(...args) {
          queries.push({sql,args});
          return {
            async first() {
              if (sql.includes('FROM works')) return work;
              return null;
            },
            async all() {
              if (sql.includes('FROM versions')) {
                // Emulate D1's SELECT aliases instead of returning raw source
                // columns from the versions table.
                if (sql.includes('JOIN works')) return {results:[{
                  ...row,
                  title:row.canonical_title,
                  artist:row.canonical_artist,
                  work_title:work.canonical_title,
                  original_artist:work.original_artist,
                }]};
                return {results:[row]};
              }
              return {results:[]};
            }
          };
        }
      };
    },
  };
}
async function post(path, body, db) {
  return worker.fetch(new Request('https://cloud.test'+path, {
    method:'POST', headers:{'content-type':'application/json'},
    body:JSON.stringify(body),
  }), {DB:db}, {});
}
test('LAB46 Cloud archive returns actual stored poster, exact video and ordered metadata', async()=>{
  const db = dbForRead();
  const response=await post('/api/v1/archive/search',{query:'Il mondo'},db);
  assert.equal(response.status,200);
  const payload=await response.json();
  assert.equal(payload.items.length,1);
  const item=payload.items[0];
  assert.equal(item.title,'Il mondo');
  assert.equal(item.artist,'Cantante giovane');
  assert.equal(item.coverUrl,sample.cover_url);
  assert.equal(item.playbackVideoId,sample.playback_video_id);
  assert.equal(item.playbackVideoTitle,sample.video_title);
  assert.equal(item.releaseDate,'2024-05-18');
  assert.equal(item.country,'IT');
  assert.deepEqual(item.credits.composers,['Jimmy Fontana','Carlo Pes']);
  assert.deepEqual(item.credits.lyricists,['Gianni Meccia']);
  assert.deepEqual(item.credits.producers,['Produttore']);
  assert.deepEqual(item.creditDetails,[{name:'Cantante giovane',role:'Lead vocals'},{name:'Fonico',role:'Sound engineer'}]);
  assert.deepEqual(item.sourceNames,['MusicBrainz','Discogs']);
  assert.equal(item.versionFingerprint,'cover-live-42');
  assert.equal(item.legacyScore20,16);
  assert.equal(item.musiclabScore100,81);
  assert.equal(item.coverRound,2);
  assert.equal(item.roundPosition,74);
  assert.equal(item.workRelationConfirmed,false);
});
test('LAB46 memory/discover returns the same complete record before fresh discovery',async()=>{
  const db=dbForRead();
  const response=await post('/api/v1/memory/discover', {
    title:'Il mondo',artist:'Jimmy Fontana',mode:'cover',
  },db);
  assert.equal(response.status,200);
  const payload=await response.json();
  assert.equal(payload.provenienza,'memoria');
  assert.equal(payload.versions.length,1);
  const item=payload.versions[0];
  assert.equal(item.coverUrl,sample.cover_url);
  assert.equal(item.playbackVideoId,sample.playback_video_id);
  assert.equal(item.musiclabScore100,81);
  assert.equal(item.legacyScore20,16);
  assert.equal(item.roundPosition,74);
  assert.equal(item.releaseDate,'2024-05-18');
  assert.deepEqual(item.credits.composers,['Jimmy Fontana','Carlo Pes']);
  assert.equal(item.creditDetails[1].role,'Sound engineer');
});
test('LAB46 archive does not fabricate unavailable cover, video, credit or dates',async()=>{
  const db=dbForRead({
    ...sample,cover_url:null,playback_video_id:null,video_title:null,
    release_date:null,credits_json:null,legacy_score_20:null,
    musiclab_score_100:null,cover_round:null,round_position:null,
    source_names_json:null,credits_detail_json:null,
  });
  const response=await post('/api/v1/archive/search',{query:'Il mondo'},db);
  assert.equal(response.status,200);
  const item=(await response.json()).items[0];
  assert.equal(item.coverUrl,null);
  assert.equal(item.playbackVideoId,null);
  assert.equal(item.releaseDate,null);
  assert.equal(item.musiclabScore100,null);
  assert.equal(item.legacyScore20,null);
  assert.equal(item.coverRound,null);
  assert.deepEqual(item.credits,{});
  assert.deepEqual(item.sourceNames,[]);
  assert.deepEqual(item.creditDetails,[]);
});
