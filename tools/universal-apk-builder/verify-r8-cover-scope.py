#!/usr/bin/env python3
"""Guard approved post-R7 evidence/final-search/original-only ownership."""
from pathlib import Path
import subprocess,sys
base=sys.argv[1] if len(sys.argv)>1 else 'f2019cf7086b71fe468d78f87b52d5b6b0d75d49'
root='app/src/main/kotlin/com/metrolist/music/ui/component/'
owners=('CoverLabTurboRoundAdmission.kt','CoverPriorityOrder.kt','CoverRoundSearchPlan.kt',
 'CoverRoundPresentation.kt','CoverDiscoverySources.kt','CoverOriginalResolver.kt',
 'GeminiCoverVerification.kt','MusicBrainzCoverSource.kt','CoverRoundStorage.kt',
 'DiscogsVersionSource.kt','DiscogsDirectVersionBrowser.kt')
allowed={root+n for n in owners}|{
 'behaviors/musiclab-cover.json','.motorlab/R8_IMPLEMENTATION_PLAN_20261028.txt',
 'tools/universal-apk-builder/verify-r8-cover-scope.py',
 'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
 '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
}
testRoot='app/src/test/kotlin/com/metrolist/music/ui/component/'
allowed|={testRoot+n for n in ('CoverLab83EvidenceAllocationTest.kt','CoverLab83OriginalResolutionTest.kt',
 'CoverLab83PendingFinalTest.kt','CoverLab77AdmissionR2Test.kt','CoverLabTurboRoundAdmissionTest.kt',
 'CoverLab71FoundationGateTest.kt','CoverLab76ProgressiveRoundTest.kt','CoverLab82PacketPriorityTest.kt')}
changed=set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed<=allowed,changed-allowed
subprocess.run(['git','diff','--check',base],check=True)
p=root+'DiscogsDirectVersionBrowser.kt'
a=subprocess.check_output(['git','show',base+':'+p],text=True); b=Path(p).read_text()
def without(text,start,end):
 before,rest=text.split(start,1);_,after=rest.split(end,1);return before+start+end+after
for start,end in [('private data class DirectVersionSession(', 'private object DirectVersionSessionStore {'),
 ('    // Read the LAST_GOOD archive asynchronously,', '    DisposableEffect(sessionKey) {'),
 ('    fun runSearch(', '        // A further Cover round never clears or reorders already committed')]:
 a,b=without(a,start,end),without(b,start,end)
assert a.rstrip()==b.rstrip(),'Player, UI/cards/header/footer or unrelated search changed'
for path in ['app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt',
 'app/src/main/res/values/metrolist_strings.xml']:
 assert subprocess.check_output(['git','show',base+':'+path],text=True).rstrip()==Path(path).read_text().rstrip(),path
print('PASS: R8 scope, native player/UI/cards, other modes and resources preserved')
