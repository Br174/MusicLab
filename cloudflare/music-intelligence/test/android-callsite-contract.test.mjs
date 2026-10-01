import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

function composableCalls(source, name) {
  const token = `${name}(`;
  const calls = [];
  let cursor = 0;

  while (cursor < source.length) {
    const start = source.indexOf(token, cursor);
    if (start < 0) break;
    cursor = start + token.length;

    const prefix = source.slice(Math.max(0, start - 32), start);
    if (/\bfun\s*$/.test(prefix)) continue;

    let depth = 1;
    let i = cursor;
    while (i < source.length && depth > 0) {
      const char = source[i];
      if (char === '(') depth += 1;
      else if (char === ')') depth -= 1;
      i += 1;
    }
    assert.equal(depth, 0, `${name}: unterminated call starting at offset ${start}`);
    calls.push(source.slice(start, i));
    cursor = i;
  }

  return calls;
}

function assertTitleSearchIsWired(path, composable, minimumCalls) {
  const source = readFileSync(path, 'utf8');
  const calls = composableCalls(source, composable);
  assert.ok(calls.length >= minimumCalls, `${composable}: expected at least ${minimumCalls} call sites`);

  const missing = calls
    .map((call, index) => ({ index: index + 1, wired: call.includes('onTitleSearch =') }))
    .filter(item => !item.wired)
    .map(item => item.index);

  assert.deepEqual(
    missing,
    [],
    `${composable}: every call site must explicitly wire onTitleSearch; missing at call(s) ${missing.join(', ')}`,
  );
}

test('OriginalVersionRow call sites all wire title search after the LAB20 review action change', () => {
  assertTitleSearchIsWired(
    'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt',
    'OriginalVersionRow',
    5,
  );
});

test('AiCoverResultRow call sites all wire title search after the LAB20 review action change', () => {
  assertTitleSearchIsWired(
    'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt',
    'AiCoverResultRow',
    2,
  );
});
