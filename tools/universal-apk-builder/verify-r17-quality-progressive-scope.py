#!/usr/bin/env python3
"""Isolated R17 change boundary: exact signed Turbo R15 and protected player/UI."""
from pathlib import Path
import subprocess,json
BASE="b35cc61c18daca9d4752fac201dcacf28a35015f"
ALLOWED=[
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
  "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmissionTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGateTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab83EvidenceAllocationTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR15RootCauseEvidenceTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR13DocumentaryVideoJoinTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR12VideoThroughputTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR9EvidenceRecoveryTest.kt",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab77PriorityQueueTest.kt",
  "behaviors/musiclab-cover.json",
  "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR17QualityProgressiveTest.kt",
  "cloudflare/music-intelligence/test/turbo-r17-quality-progressive.test.mjs",
  "tools/universal-apk-builder/prepare-turbo-r17-quality-progressive-env.py",
  ".github/workflows/musiclab-turbo-r17-quality-progressive-apk.yml",
  "tools/universal-apk-builder/verify-r17-quality-progressive-scope.py"
]
subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
diff=set(subprocess.check_output(['git','diff','--name-only',BASE],text=True).splitlines())
assert diff<=set(ALLOWED),f'Out-of-scope R17 changes: {diff-set(ALLOWED)}'
subprocess.run(['git','diff','--check',BASE],check=True)
def base(path): return subprocess.check_output(['git','show',BASE+':'+path],text=True)
protected=[
 'app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt',
 'app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt',
 'app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt',
 'app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGate.kt',
 'app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboTwinGuard.kt',
 'app/src/main/kotlin/com/metrolist/music/ui/component/TitleMeaningResolver.kt',
 'app/build.gradle.kts',
]
for path in protected:
 assert Path(path).read_text()==base(path),f'Protected player/UI/source changed: {path}'
root=Path('app/src/main/kotlin/com/metrolist/music/ui/component')
a=(root/'CoverLabTurboRoundAdmission.kt').read_text()
p=(root/'CoverPriorityOrder.kt').read_text()
assert 'fun minimumRound(' in a and '): Int = 1' in a
assert 'return Verdict(sameWork,' in a
assert 'heldUntilRound' not in p and 'thenBy { it.minimumRound > round }' not in p
assert 'fun deferUntil(' in p and 'fun eligible(' in p
m=json.loads(Path('behaviors/musiclab-cover.json').read_text())
rules={r['id']:r for r in m['rules']}
assert rules['musiclab.cover.round-evidence-allocation.v1']['status']=='retired'
assert rules['musiclab.cover.quality-progressive.r17']['status']=='active'
assert m['approvedR17QualityProgressive']['cycleSizes']==[10,20,20]
print('R17 SCOPE PASS: R15 ancestor, only approved files, old evidence gates gone, player/UI/packaging untouched')
