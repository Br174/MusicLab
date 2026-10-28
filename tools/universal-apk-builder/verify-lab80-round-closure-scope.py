#!/usr/bin/env python3
"""Protect delivered R4 algorithms while updating only round progression."""
from pathlib import Path
import subprocess

base = '84a858f8e4c95c9d11ce27d0e5a77fb2f8bb0c27'
root = 'app/src/main/kotlin/com/metrolist/music/ui/component/'
allowed = {root + n for n in ('CoverRoundPresentation.kt', 'CoverRoundSearchPlan.kt',
    'CoverPriorityOrder.kt', 'DiscogsDirectVersionBrowser.kt')} | {
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab76ProgressiveRoundTest.kt',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab78PresentationTest.kt',
    'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab80RoundClosureTest.kt',
    'app/src/main/res/values/metrolist_strings.xml',
    'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
    'tools/universal-apk-builder/verify-lab80-round-closure-scope.py',
    '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml'}
def old(path):
    return subprocess.check_output(['git','show',base+':'+path],text=True)
changed = set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed <= allowed, changed-allowed
subprocess.run(['git','diff','--check',base],check=True)
path = root+'CoverPriorityOrder.kt'
s = Path(path).read_text().replace(', retryCompleted: Boolean = false','')
s = s.replace('            if (retryCompleted) done.remove(key)\n','')
s = s.replace(', texts: Set<String>? = null','')
s = s.replace(' && (texts == null || it.value.text in texts)','')
assert s.rstrip() == old(path).rstrip(), 'Ranking or retained queue behavior changed'
path = root+'CoverRoundSearchPlan.kt'
s = Path(path).read_text()
s = s.replace('    val finalRound: Int get() = 4 + (longTail.size + 3) / 4\n\n','')
s = s.replace('    /** All existing families are visited before the terminal title-only search. */',
              '    /** No percentage quotas, no fixed publication blocks. */')
s = s.replace('        if (round >= finalRound) return listOf(Query(clean, AiCoverCategory.COVER))\n','')
assert s.rstrip() == old(path).rstrip(), 'Existing source queries changed'
path = root+'DiscogsDirectVersionBrowser.kt'
a,b = old(path), Path(path).read_text()
start = '    fun runSearch('
assert a.split(start,1)[0] == b.split(start,1)[0], 'Player/lifecycle/header changed'
tail = '        // A further Cover round never clears or reorders already committed'
a,b = a.split(tail,1)[1], b.split(tail,1)[1]
button = '            if (isTurbo && mode == DiscogsDirectMode.COVER && activeCriteria != null &&'
end = '            if (\n                mode == DiscogsDirectMode.COVER &&\n                !isTurbo'
assert a.split(button,1)[0] == b.split(button,1)[0], 'Legacy search or cards changed'
assert a.split(button,1)[1].split(end,1)[1].rstrip() == b.split(button,1)[1].split(end,1)[1].rstrip(), 'Other UI changed'
path = 'app/src/main/res/values/metrolist_strings.xml'
s = Path(path).read_text()
s = ''.join(line for line in s.splitlines(keepends=True) if not any(
    'name="'+name+'"' in line for name in ('musiclab_cover_none_final','musiclab_cover_finished','musiclab_cover_retry_final')))
assert s.rstrip() == old(path).rstrip(), 'Unrelated strings changed'
print('PASS: R4 player, source parsers, admission, ranking, normalization and card layout preserved')
