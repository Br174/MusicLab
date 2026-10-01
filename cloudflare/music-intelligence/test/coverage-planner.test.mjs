import test from 'node:test';
import assert from 'node:assert/strict';
import * as brainWorker from '../src/worker-v20.js';

function coverageDb(existing = []) {
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
              if (sql.includes('FROM coverage_cells')) return { results: existing };
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

test('Coverage planner chooses only unsearched gaps and registers missions as in-flight', async () => {
  assert.equal(typeof brainWorker.planCoverageMissions, 'function');

  const db = coverageDb([
    { family: 'version_type', dimension_key: 'studio_cover', state: 'searched' },
    { family: 'language_adaptation', dimension_key: 'inglese', state: 'in_flight' },
  ]);

  const missions = await brainWorker.planCoverageMissions(db, {
    title: 'Il mondo',
    artist: 'Jimmy Fontana',
    mode: 'cover',
    year: 1965,
  }, 6);

  assert.ok(missions.length > 0 && missions.length <= 6);
  assert.ok(!missions.some(mission => mission.family === 'version_type' && mission.dimensionKey === 'studio_cover'));
  assert.ok(!missions.some(mission => mission.family === 'language_adaptation' && mission.dimensionKey === 'inglese'));
  assert.ok(missions.some(mission => mission.family === 'era' && mission.dimensionKey === '1960s'));
  assert.equal(db.writes.filter(entry => entry.sql.includes('search_missions')).length, missions.length);
  assert.equal(db.writes.filter(entry => entry.sql.includes('coverage_cells')).length, missions.length);
});

test('Coverage completion closes missions and cells instead of leaving them in-flight', async () => {
  assert.equal(typeof brainWorker.completeCoverageMissions, 'function');

  const db = coverageDb();
  const missions = [{
    id: 'mission-1',
    workId: 'work-1',
    family: 'version_type',
    dimensionKey: 'studio_cover',
  }];

  await brainWorker.completeCoverageMissions(db, missions, 3);

  const missionUpdate = db.writes.find(entry => entry.sql.includes('UPDATE search_missions'));
  const cellUpdate = db.writes.find(entry => entry.sql.includes('UPDATE coverage_cells'));
  assert.ok(missionUpdate);
  assert.ok(cellUpdate);
  assert.equal(missionUpdate.args[0], 'completed');
  assert.equal(cellUpdate.args[0], 'searched');
  assert.equal(cellUpdate.args[1], 3);
});
