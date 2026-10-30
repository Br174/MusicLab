#!/usr/bin/env python3
"""R12 isolated native patch scope: exact verified r40 active Turbo parent."""
import subprocess
from pathlib import Path
BASE="4a7d24440120e90bda1f95c4ea6f8eb121d04710"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
 ROOT+"CoverRoundPresentation.kt",
 ROOT+"DiscogsDirectVersionBrowser.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR12VerifiedResidualTest.kt",
 ".github/workflows/musiclab-turbo-r10-empty-packet-apk.yml",
 ".github/workflows/musiclab-turbo-r12-verified-residual-apk.yml",
 "tools/universal-apk-builder/verify-r12-specific-scope.py",
 "tools/universal-apk-builder/prepare-turbo-r12-verified-residual-env.py",
 ".motorlab/R12_VERIFIED_RESIDUAL_CHECKPOINT_20261030.txt",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=ALLOWED,changed-ALLOWED
subprocess.run(["git","diff","--check",BASE],check=True)
path=ROOT+"DiscogsDirectVersionBrowser.kt"
before=subprocess.check_output(["git","show",BASE+":"+path],text=True)
after=Path(path).read_text()
def without(s,a,b):
    assert s.count(a)==1,(a,s.count(a))
    head,rest=s.split(a,1)
    assert rest.count(b)>=1,(b,rest.count(b))
    _,tail=rest.split(b,1)
    return head+a+b+tail
# Only the existing Cover Coordinator changes, never the player or other modes.
start="    fun runSearch("
end="        // A further Cover round never clears or reorders already committed"
before,after=without(before,start,end),without(after,start,end)
old_ui="""                                if (presentationStatus.manualContinue) {
                                    session.presentation.continueEmptyWindow()
                                    presentationStatus = session.presentation.status()
                                    roundPlanComplete = presentationStatus.green
                                    session.roundPlanComplete = roundPlanComplete
                                } else {
                                    runSearch(forceRefresh = true, appendRound = true)
                                }"""
new_ui="""                                // Manual publication stays in the native coordinator so
                                // prepared rows reach the same stable Compose snapshots.
                                runSearch(forceRefresh = true, appendRound = true)"""
assert after.count(new_ui)==1
after=after.replace(new_ui,old_ui,1)
assert after==before,"R12 changed an unrelated player, UI or non-Cover segment"
for name in ["TitleMeaningResolver.kt","CoverLabTurboRoundAdmission.kt","CoverDiscoverySources.kt","CoverPriorityOrder.kt","CoverOriginalResolver.kt"]:
    p=ROOT+name
    assert Path(p).read_text()==subprocess.check_output(["git","show",BASE+":"+p],text=True),p
print("PASS: R12 exact parent, content isolation, native UI/player and evidence/source protections")
