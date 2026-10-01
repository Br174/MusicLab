import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const repo = resolve(new URL('../../', import.meta.url).pathname);
const read = (path) => readFile(join(repo, path), 'utf8');

test('AGENTS.override.md makes MotorLab a mandatory project bootstrap while preserving native rules', async () => {
  const agents = await read('AGENTS.override.md');
  assert.match(agents, /MotorLab bootstrap/i);
  assert.match(agents, /⚙️ MotorLab attivo/);
  assert.match(agents, /MOTORLAB_PROJECT_HOOK\.txt/);
  assert.match(agents, /.motorlab\/MOTORLAB_LOCAL_CORE\.txt/);
  assert.match(agents, /AGENTS\.md/);
  assert.match(agents, /coexist|preserve/i);
});

test('Codex SessionStart hook loads MotorLab on startup resume clear and compact', async () => {
  const cfg = JSON.parse(await read('.codex/hooks.json'));
  assert.ok(cfg.hooks.SessionStart);
  const raw = JSON.stringify(cfg);
  assert.match(raw, /startup\|resume\|clear\|compact/);
  assert.match(raw, /motorlab_boot\.py/);
  assert.doesNotMatch(raw, /continue\s*[:=]\s*false/i);
});

test('bootstrap hook injects verified local MotorLab contract without network dependency', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'musiclab-motorlab-'));
  await mkdir(join(temp, '.motorlab'), { recursive: true });
  await mkdir(join(temp, '.codex', 'hooks'), { recursive: true });
  await writeFile(join(temp, 'MOTORLAB_PROJECT_HOOK.txt'), 'PROJECT_NAME=MusicLab\nMOTORLAB_CONTROL_REPOSITORY=Br174/Chatgpt\n');
  await writeFile(join(temp, '.motorlab', 'MOTORLAB_LOCAL_CORE.txt'), 'MOTORLAB SATELLITE BUNDLE\nRELEASE=2026.09.30-r16\nHIGH_PRESENCE_NUMERIC_PROGRESS=required\n');
  await writeFile(join(temp, '.motorlab', 'MOTORLAB_SYNC_STATE.txt'), 'PROJECT=MusicLab\nSTATE=VERIFIED\n');
  await copyFile(join(repo, '.codex', 'hooks', 'motorlab_boot.py'), join(temp, '.codex', 'hooks', 'motorlab_boot.py'));
  spawnSync('git', ['init'], { cwd: temp, encoding: 'utf8' });
  const run = spawnSync('python3', [join(temp, '.codex', 'hooks', 'motorlab_boot.py')], {
    cwd: temp,
    input: JSON.stringify({ hook_event_name: 'SessionStart', source: 'startup', cwd: temp, session_id: 's1' }),
    encoding: 'utf8'
  });
  assert.equal(run.status, 0, run.stderr);
  const out = JSON.parse(run.stdout);
  assert.equal(out.hookSpecificOutput.hookEventName, 'SessionStart');
  assert.match(out.hookSpecificOutput.additionalContext, /⚙️ MotorLab attivo/);
  assert.match(out.hookSpecificOutput.additionalContext, /RELEASE=2026\.09\.30-r16/);
  assert.match(out.hookSpecificOutput.additionalContext, /HIGH_PRESENCE_NUMERIC_PROGRESS=required/);
  assert.doesNotMatch(run.stdout, /"continue"\s*:\s*false/);
});
