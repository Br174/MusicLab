import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker-v20.js';

function memoryDb(versions = [], hasWork = true) {
  const work = hasWork ? {
    id: 'work-1',
    search_key: 'il mondo|jimmy fontana',
    canonical_title: 'Il mondo',
    original_artist: 'Jimmy Fontana',
    original_year: 1965,
    original_language: 'it',
    credits_json: JSON.stringify({ composers: ['Jimmy Fontana'] }),
    resolver_version: 12,
  } : null;

  return {
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes('FROM works WHERE search_key')) return work;
              return null;
            },
            async all() {
              if (!sql.includes('FROM versions')) return { results: [] };
              const limit = Number(args[1] ?? versions.length);
              return { results: versions.slice(0, limit) };
            },
          };
        },
      };
    },
  };
}

function version(index) {
  return {
    canonical_title: `Il mondo ${index}`,
    canonical_artist: `Artista ${index}`,
    category: 'cover',
    language: 'it',
    year: 1965 + index,
    album: null,
    credits_json: '{}',
    same_work_score: 60 + index,
    version_type_score: 75,
    decision_status: index % 2 === 0 ? 'PROBABLE' : 'UNCERTAIN',
    ai_reason: null,
  };
}

async function memoryRequest(env, body = {}) {
  return worker.fetch(new Request('https://musiclab.test/api/v1/memory/discover', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title: 'Il mondo', artist: 'Jimmy Fontana', mode: 'cover', limit: 150, ...body }),
  }), env, {});
}

async function discoveryRequest(env, phase = 'expand', body = {}) {
  return worker.fetch(new Request(`https://musiclab.test/api/v1/discover/${phase}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title: 'Il mondo', artist: 'Jimmy Fontana', mode: 'cover', useMemory: true, ...body }),
  }), env, {});
}

test('memory-only discovery returns all learned D1 candidates without Gemini', async () => {
  const versions = Array.from({ length: 12 }, (_, index) => version(index));
  const response = await memoryRequest({ DB: memoryDb(versions) });
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.provenienza, 'memoria');
  assert.equal(payload.versions.length, 12);
  assert.equal(payload.versions[0].sameWorkScore, 60);
  assert.equal(payload.versions[0].brainStatus, 'PROBABLE');
});

test('empty memory is a normal empty response and never requires an AI key', async () => {
  const response = await memoryRequest({ DB: memoryDb([], false) });
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.provenienza, 'memoria-vuota');
  assert.deepEqual(payload.versions, []);
});

test('memory-only discovery is bounded even when caller asks for too much', async () => {
  const versions = Array.from({ length: 180 }, (_, index) => version(index));
  const response = await memoryRequest({ DB: memoryDb(versions) }, { limit: 9999 });
  const payload = await response.json();
  assert.equal(payload.versions.length, 150);
});

test('expand serves unseen D1 memory before Gemini and does not require an AI key', async () => {
  const versions = Array.from({ length: 12 }, (_, index) => version(index));
  const existing = versions.slice(0, 5).map(item => ({
    title: item.canonical_title,
    artist: item.canonical_artist,
    category: item.category,
    language: item.language,
  }));

  const response = await discoveryRequest({ DB: memoryDb(versions) }, 'expand', { existing });
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.provenienza, 'memoria');
  assert.equal(payload.fase, 'expand');
  assert.equal(payload.versions.length, 7);
  assert.equal(payload.versions[0].title, 'Il mondo 5');
  assert.equal(payload.versions[0].brainStatus, 'UNCERTAIN');
});
