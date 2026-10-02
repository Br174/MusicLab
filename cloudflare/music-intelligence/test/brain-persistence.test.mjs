import test from 'node:test';
import assert from 'node:assert/strict';
import * as brainWorker from '../src/worker-v20.js';

function persistenceDb() {
  const writes = [];
  return {
    writes,
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if (sql.includes('FROM works')) return { id: 'work-1' };
              return null;
            },
            async all() {
              if (sql.includes('FROM versions')) {
                return {
                  results: [{
                    id: 'version-1',
                    version_key: 'cover|milva|il mondo|it',
                    same_work_score: 50,
                    version_type_score: 50,
                    decision_status: 'UNCERTAIN',
                  }],
                };
              }
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
  };
}

function brainPayload() {
  return {
    original: {
      title: 'Il mondo',
      artist: 'Jimmy Fontana',
      year: 1965,
      language: 'it',
      credits: {
        songwriters: ['Gianni Meccia'],
        composers: ['Jimmy Fontana', 'Carlo Pes'],
        lyricists: ['Gianni Meccia'],
      },
    },
    versions: [{
      title: 'Il mondo',
      artist: 'Milva',
      category: 'cover',
      language: 'it',
      sameWorkScore: 88,
      versionTypeScore: 75,
      brainStatus: 'PROBABLE',
      brainAdmission: 'two_key_rule',
      brainSignals: [{ kind: 'composer_match', strength: 'strong', direction: 'positive' }],
    }],
  };
}

test('Brain scores, decision and evidence are persisted beside the learned version', async () => {
  assert.equal(typeof brainWorker.persistBrainAnnotations, 'function');

  const db = persistenceDb();
  await brainWorker.persistBrainAnnotations(db, {
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
  }, brainPayload());

  const update = db.writes.find(entry => entry.sql.includes('UPDATE versions'));
  assert.ok(update, 'expected versions score/status update');
  assert.deepEqual(update.args.slice(0, 5), [88, 75, 'PROBABLE', 'two_key_rule', 'version-1']);

  const history = db.writes.find(entry => entry.sql.includes('INSERT INTO decision_history'));
  assert.ok(history, 'expected AI decision history row');
  assert.equal(history.args[2], 'PROBABLE');
  assert.equal(history.args[3], 88);
  assert.equal(history.args[4], 75);

  const evidence = db.writes.find(entry => entry.sql.includes('INSERT INTO version_evidence'));
  assert.ok(evidence, 'expected Brain evidence row');
  assert.equal(evidence.args[2], 'brain');
  assert.equal(evidence.args[3], 'composer_match');
  assert.equal(evidence.args[4], 'strong');
  assert.equal(evidence.args[5], 'positive');
});

test('Work Signature, identifiers, aliases and translated titles are persisted without becoming mandatory', async () => {
  assert.equal(typeof brainWorker.persistWorkSignature, 'function');

  const db = persistenceDb();
  const signature = {
    canonicalTitle: 'Il mondo',
    originalArtist: 'Jimmy Fontana',
    aliases: ['Il Mondo (My World)', 'The World'],
    translatedTitles: ['El mundo', 'El món'],
    writers: ['Gianni Meccia'],
    composers: ['Jimmy Fontana', 'Carlo Pes'],
    lyricists: ['Gianni Meccia'],
    publishers: [],
    iswc: 'T-005.001.002-0',
    musicbrainzWorkId: 'mb-work-1',
    year: 1965,
    language: 'it',
  };

  await brainWorker.persistWorkSignature(db, signature);

  const workUpdate = db.writes.find(entry => entry.sql.includes('UPDATE works'));
  assert.ok(workUpdate, 'expected Work Signature update');
  assert.equal(workUpdate.args[1], 'T-005.001.002-0');
  assert.equal(workUpdate.args[2], 'mb-work-1');
  assert.equal(workUpdate.args[3], 'work-1');
  assert.deepEqual(JSON.parse(workUpdate.args[0]).translatedTitles, ['El mondo'.replace('mondo', 'mundo'), 'El món']);

  const aliasWrites = db.writes.filter(entry => entry.sql.includes('work_aliases'));
  assert.equal(aliasWrites.length, 4);
  assert.deepEqual(aliasWrites.map(entry => entry.args[1]), [
    'Il Mondo (My World)',
    'The World',
    'El mundo',
    'El món',
  ]);
  assert.deepEqual(aliasWrites.map(entry => entry.args[3]), [
    'alternate',
    'alternate',
    'translated',
    'translated',
  ]);
});

test('fresh discovery has one complete persistence path for Work Signature and Brain annotations', async () => {
  assert.equal(typeof brainWorker.persistDiscoveryBrainMemory, 'function');

  const db = persistenceDb();
  await brainWorker.persistDiscoveryBrainMemory(db, {
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    aliases: ['The World'],
    translatedTitles: ['El mundo'],
    iswc: null,
    musicbrainzWorkId: null,
  }, brainPayload());

  assert.ok(db.writes.some(entry => entry.sql.includes('UPDATE works')), 'expected Work Signature persistence');
  assert.ok(db.writes.some(entry => entry.sql.includes('work_aliases')), 'expected alias persistence');
  assert.ok(db.writes.some(entry => entry.sql.includes('UPDATE versions')), 'expected Brain annotation persistence');
  assert.ok(db.writes.some(entry => entry.sql.includes('decision_history')), 'expected AI decision history persistence');
});
