import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker-v20-parallel.js';

function dbWithCanonicalMemory() {
  const writes = [];
  const work = {
    id: 'work-1',
    search_key: 'il mondo|jimmy fontana',
    canonical_title: 'Il mondo',
    original_artist: 'Jimmy Fontana',
    original_year: 1965,
    original_language: 'it',
    credits_json: JSON.stringify({ composers: ['Carlo Pes'] }),
    resolver_version: 12,
  };

  return {
    writes,
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes('FROM works WHERE search_key')) return work;
              return null;
            },
            async all() {
              if (sql.includes('FROM versions')) return { results: [] };
              if (sql.includes('FROM coverage_cells')) return { results: [] };
              return { results: [] };
            },
            async run() {
              writes.push({ sql, args });
              return { success: true };
            },
          };
        },
      };
    },
    async batch(statements) {
      writes.push({ sql: 'BATCH', args: [statements.length] });
      return [];
    },
  };
}

function discoveryResponse(index) {
  const payload = {
    original: {
      title: 'Il mondo',
      artist: 'Jimmy Fontana',
      year: 1965,
      language: 'it',
      album: null,
      credits: { songwriters: [], composers: ['Carlo Pes'], lyricists: [], producers: [], label: null },
    },
    versions: [{
      title: index === 1 ? 'Il mondo' : `Il mondo adattamento ${index}`,
      artist: `Artista corsia ${index}`,
      category: index === 1 ? 'cover' : 'straniera',
      language: index === 1 ? 'it' : index === 2 ? 'es' : 'fr',
      year: 1965 + index,
      album: null,
      credits: { songwriters: [], composers: ['Carlo Pes'], lyricists: [], producers: [], label: null },
    }],
  };
  return new Response(JSON.stringify({
    candidates: [{ content: { parts: [{ text: JSON.stringify(payload) }] } }],
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

async function expand(db) {
  return worker.fetch(new Request('https://musiclab.test/api/v1/discover/expand', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      title: 'Il mondo',
      artist: 'Jimmy Fontana',
      mode: 'cover',
      useMemory: true,
      existing: [],
    }),
  }), { DB: db, GEMINI_API_KEY: 'test-key', GEMINI_MODEL: 'test-model' }, {});
}

test('cover expand fans Coverage Map into three focused AI lanes and reuses canonical D1 identity', async () => {
  const db = dbWithCanonicalMemory();
  const prompts = [];
  let discoveryIndex = 0;
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async (_url, init = {}) => {
    const body = JSON.parse(String(init.body || '{}'));
    const prompt = String(body?.contents?.[0]?.parts?.[0]?.text || '');
    if (prompt) prompts.push(prompt);

    discoveryIndex += 1;
    return discoveryResponse(discoveryIndex);
  };

  try {
    const response = await expand(db);
    assert.equal(response.status, 200);
    const payload = await response.json();

    const resolverPrompts = prompts.filter(prompt => /resolver musicale canonico/i.test(prompt));
    const researchPrompts = prompts.filter(prompt => /ricercatore discografico AI centrale/i.test(prompt));
    assert.equal(resolverPrompts.length, 0);
    assert.equal(researchPrompts.length, 3);
    assert.equal(new Set(researchPrompts).size, 3);
    assert.equal(payload.versions.length, 3);
    assert.deepEqual(
      payload.versions.map(item => item.artist).sort(),
      ['Artista corsia 1', 'Artista corsia 2', 'Artista corsia 3'],
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
