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
 'behaviors/musiclab-cover.json','.motorlab/MUSICLAB_COVER_SIX_ROUNDS_APPROVED_SPEC_20261028.txt','.motorlab/R8_IMPLEMENTATION_PLAN_20261028.txt',
 'tools/universal-apk-builder/verify-r8-cover-scope.py',
 'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
 '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
}
testRoot='app/src/test/kotlin/com/metrolist/music/ui/component/'
allowed|={testRoot+n for n in ('CoverLab83EvidenceAllocationTest.kt','CoverLab83OriginalResolutionTest.kt',
 'CoverLab83PendingFinalTest.kt','CoverLab77AdmissionR2Test.kt','CoverLabTurboRoundAdmissionTest.kt',
 'CoverLab71FoundationGateTest.kt','CoverLab76ProgressiveRoundTest.kt','CoverLab82PacketPriorityTest.kt','CoverLab77PriorityQueueTest.kt','CoverLab70FirstRoundRecoveryTest.kt','CoverRecordingMergeRegressionTest.kt')}
changed=set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed<=allowed,changed-allowed
subprocess.run(['git','diff','--check',base],check=True)
p=root+'DiscogsDirectVersionBrowser.kt'
a=subprocess.check_output(['git','show',base+':'+p],text=True); b=Path(p).read_text()
# The ornamental-only amendment permits exactly these score-neutral tie-breaks
# in otherwise protected automatic/manual video replacement code.
a=a.replace('.thenByDescending { it.confidenceScore }', '.thenByDescending { if (isTurbo && mode == DiscogsDirectMode.COVER) 0 else it.confidenceScore }')
a=a.replace('kotlin.math.abs(it.confidenceScore - missing.confidenceScore)', 'if (isTurbo && mode == DiscogsDirectMode.COVER) 0 else kotlin.math.abs(it.confidenceScore - missing.confidenceScore)')
a=a.replace('quick.sortedByDescending { it.evidenceScore }', 'if (isTurbo && mode == DiscogsDirectMode.COVER) quick else quick.sortedByDescending { it.evidenceScore }')
a=a.replace('items.sortedByDescending { it.evidenceScore }', 'if (isTurbo && mode == DiscogsDirectMode.COVER) items else items.sortedByDescending { it.evidenceScore }')
def without(text,start,end):
 before,rest=text.split(start,1);_,after=rest.split(end,1);return before+start+end+after
for start,end in [('private data class DirectVersionSession(', 'private object DirectVersionSessionStore {'),
 ('    // Read the LAST_GOOD archive asynchronously,', '    DisposableEffect(sessionKey) {'),
 ('    fun normalizeSearchLocalVideoBindings(', '    fun '),
 ('    fun sortGroup(', '    fun sortedPool('),
 ('    fun videoLookupPriority(', '    fun videoPriorityPool('),
 ('    fun autoVideoAttemptBudget(', '    fun needsAutomaticVideo('),
 ('    fun runSearch(', '        // A further Cover round never clears or reorders already committed')]:
 a,b=without(a,start,end),without(b,start,end)
assert a.rstrip()==b.rstrip(),'Player, UI/cards/header/footer or unrelated search changed'
for path in ['app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt',
 'app/src/main/res/values/metrolist_strings.xml']:
 assert subprocess.check_output(['git','show',base+':'+path],text=True).rstrip()==Path(path).read_text().rstrip(),path
print('PASS: R8 scope, native player/UI/cards, other modes and resources preserved')
