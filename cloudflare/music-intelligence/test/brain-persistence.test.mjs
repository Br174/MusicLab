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

test('Brain scores, decision and evidence are persisted beside the learned version', async () => {
  assert.equal(typeof brainWorker.persistBrainAnnotations, 'function');

  const db = persistenceDb();
  await brainWorker.persistBrainAnnotations(db, {
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
  }, {
    original: { title: 'Il mondo', artist: 'Jimmy Fontana' },
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
  });

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
