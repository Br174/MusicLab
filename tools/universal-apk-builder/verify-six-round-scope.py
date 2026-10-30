#!/usr/bin/env python3
"""Limit the six-round update to its approved native owners."""
from pathlib import Path
import re
import subprocess
import sys

base = sys.argv[1] if len(sys.argv) > 1 else 'e7f54f5b5df8d564521e2b46a8a0fd3b483a1713'
root = 'app/src/main/kotlin/com/metrolist/music/ui/component/'
allowed = {root+n for n in ('CoverRoundSearchPlan.kt','CoverPriorityOrder.kt',
    'CoverRoundPresentation.kt','DiscogsDirectVersionBrowser.kt')} | {
    'app/src/test/kotlin/com/metrolist/music/ui/component/'+n for n in (
        'CoverLab76ProgressiveRoundTest.kt','CoverLab80RoundClosureTest.kt',
        'CoverLab81SixRoundTest.kt','CoverLab81FinalContinuationTest.kt',
        'CoverRecordingMergeRegressionTest.kt')
} | {
    'behaviors/musiclab-cover.json',
    '.motorlab/SIX_ROUNDS_IMPLEMENTATION_PLAN_20261028.txt',
    'tools/universal-apk-builder/verify-six-round-scope.py',
    'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
    '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
}
def old(path):
    return subprocess.check_output(['git','show',base+':'+path],text=True)
changed=set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed <= allowed, changed-allowed
subprocess.run(['git','diff','--check',base],check=True)

path=root+'CoverRoundSearchPlan.kt'
a,b=old(path),Path(path).read_text()
def literals(text):
    return set(re.findall(r'"([^"\n]+)"',text))
official=lambda text: text.split('private val official = listOf(',1)[1].split('    )',1)[0]
assert official(a)==official(b), 'First-round query family changed'
for name in ('official','talent','amateur','longTail'):
    block=a.split('private val '+name+' = listOf(',1)[1].split('    )',1)[0]
    assert literals(block)<=literals(b), (name,'descriptor removed')

path=root+'CoverPriorityOrder.kt'
a,b=old(path),Path(path).read_text()
for marker in ('    fun originalReference(', '    class DocumentaryProgress', '    class VideoQueries', '    class Pending'):
    end='    private fun merge(' if marker=='    fun originalReference(' else None
    left=a.split(marker,1)[1]
    right=b.split(marker,1)[1]
    if end:
        left,right=left.split(end,1)[0],right.split(end,1)[0]
    assert left.rstrip()==right.rstrip(), 'Attribution, binding or cursor ownership changed: '+marker

path=root+'DiscogsDirectVersionBrowser.kt'
a,b=old(path),Path(path).read_text()
start='    fun runSearch('
assert a.split(start,1)[0]==b.split(start,1)[0], 'Player/header/lifecycle changed'
tail='        // A further Cover round never clears or reorders already committed'
a,b=a.split(tail,1)[1],b.split(tail,1)[1]
button='            if (isTurbo && mode == DiscogsDirectMode.COVER && activeCriteria != null &&'
end='            if (\n                mode == DiscogsDirectMode.COVER &&\n                !isTurbo'
assert a.split(button,1)[0]==b.split(button,1)[0], 'Legacy search or cards changed'
assert a.split(button,1)[1].split(end,1)[1].rstrip()==b.split(button,1)[1].split(end,1)[1].rstrip(), 'Other UI changed'
print('PASS: scope, first-round queries, descriptor inventory, attribution, cursor queues, player and cards preserved')
