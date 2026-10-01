import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker-v20.js';

function integrationDb() {
  const writes = [];
  const work = {
    id: 'work-1',
    search_key: 'il mondo|jimmy fontana',
    canonical_title: 'Il mondo',
    original_artist: 'Jimmy Fontana',
    original_year: 1965,
    original_language: 'it',
    credits_json: JSON.stringify({ composers: ['Jimmy Fontana'] }),
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

function geminiResponse() {
  const discovery = {
    original: {
      title: 'Il mondo',
      artist: 'Jimmy Fontana',
      year: 1965,
      language: 'it',
      album: null,
      credits: { songwriters: [], composers: ['Jimmy Fontana'], lyricists: [], producers: [], label: null },
    },
    versions: [{
      title: 'Il mondo',
      artist: 'Milva',
      category: 'cover',
      language: 'it',
      year: 1967,
      album: null,
      credits: { songwriters: [], composers: ['Jimmy Fontana'], lyricists: [], producers: [], label: null },
    }],
  };
  return new Response(JSON.stringify({
    candidates: [{ content: { parts: [{ text: JSON.stringify(discovery) }] } }],
  }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
}

test('expand uses fresh Coverage Map missions after D1 memory is exhausted and closes them on success', async () => {
  const db = integrationDb();
  const prompts = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, init = {}) => {
    const body = JSON.parse(String(init.body || '{}'));
    const prompt = body?.contents?.[0]?.parts?.[0]?.text;
    if (prompt) prompts.push(prompt);
    return geminiResponse();
  };

  try {
    const response = await worker.fetch(new Request('https://musiclab.test/api/v1/discover/expand', {
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

    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.fase, 'expand');
    assert.equal(payload.versions.length, 1);

    const combinedPrompt = prompts.join('\n');
    assert.match(combinedPrompt, /1960s/i);
    assert.match(combinedPrompt, /studio cover|studio_cover/i);

    const missionInserts = db.writes.filter(entry => entry.sql.includes('INSERT INTO search_missions'));
    const cellInFlight = db.writes.filter(entry => entry.sql.includes('INSERT INTO coverage_cells'));
    const missionUpdates = db.writes.filter(entry => entry.sql.includes('UPDATE search_missions'));
    const cellUpdates = db.writes.filter(entry => entry.sql.includes('UPDATE coverage_cells'));
    assert.ok(missionInserts.length > 0);
    assert.equal(cellInFlight.length, missionInserts.length);
    assert.equal(missionUpdates.length, missionInserts.length);
    assert.equal(cellUpdates.length, missionInserts.length);
    assert.ok(missionUpdates.every(entry => entry.args[0] === 'completed'));
    assert.ok(cellUpdates.every(entry => entry.args[0] === 'searched'));
  } finally {
    globalThis.fetch = originalFetch;
  }
});
