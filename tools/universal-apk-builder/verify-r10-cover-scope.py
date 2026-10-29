#!/usr/bin/env python3
"""Guard approved post-R7 evidence/final-search/original-only ownership."""
from pathlib import Path
import subprocess,sys
base=sys.argv[1] if len(sys.argv)>1 else '936a620bbac6069906ef0c34e19c4787e4234bb8'
root='app/src/main/kotlin/com/metrolist/music/ui/component/'
owners=('CoverLabTurboRoundAdmission.kt','TitleMeaningResolver.kt','CoverPriorityOrder.kt','CoverRoundSearchPlan.kt',
 'CoverRoundPresentation.kt','CoverDiscoverySources.kt','CoverOriginalResolver.kt',
 'GeminiCoverVerification.kt','MusicBrainzCoverSource.kt','CoverRoundStorage.kt',
 'DiscogsVersionSource.kt','DiscogsDirectVersionBrowser.kt')
allowed={root+n for n in owners}|{
 'behaviors/musiclab-cover.json','.motorlab/MUSICLAB_R8_DELIVERY_CHECKPOINT_20261029.txt','.motorlab/MUSICLAB_COVER_SIX_ROUNDS_APPROVED_SPEC_20261028.txt','.motorlab/R8_IMPLEMENTATION_PLAN_20261028.txt',
 'tools/universal-apk-builder/verify-r8-cover-scope.py',
 'tools/universal-apk-builder/prepare-turbo-lab79-preparation-progress-env.py',
 '.github/workflows/musiclab-turbo-lab79-preparation-progress-apk.yml',
}
testRoot='app/src/test/kotlin/com/metrolist/music/ui/component/'
allowed|={testRoot+n for n in ('CoverLab83EvidenceAllocationTest.kt','CoverLab83OriginalResolutionTest.kt',
 'CoverLab83PendingFinalTest.kt','CoverLab77AdmissionR2Test.kt','CoverLabTurboRoundAdmissionTest.kt',
 'CoverLab71FoundationGateTest.kt','CoverLab76ProgressiveRoundTest.kt','CoverLab82PacketPriorityTest.kt','CoverLab77PriorityQueueTest.kt','CoverLab70FirstRoundRecoveryTest.kt','CoverRecordingMergeRegressionTest.kt','CoverLab79PreparationProgressTest.kt')}
allowed |= {
 '.motorlab/R9_IMPLEMENTATION_PLAN_20261029.txt',
 '.motorlab/R9_DELIVERY_CHECKPOINT_20261029.txt',
 'tools/universal-apk-builder/verify-r9-cover-scope.py',
 'tools/universal-apk-builder/prepare-turbo-r9-recovery-env.py',
 '.github/workflows/musiclab-turbo-r9-recovery-apk.yml',
 '.github/workflows/musiclab-turbo-r10-empty-packet-apk.yml',
 'tools/universal-apk-builder/verify-r10-cover-scope.py',
 'tools/universal-apk-builder/prepare-turbo-r10-empty-packet-env.py',
 'app/src/test/kotlin/com/metrolist/music/ui/component/CoverR10EmptyRoundRecoveryTest.kt',
 'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab81FinalContinuationTest.kt',
 'app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab81SixRoundTest.kt',
 '.motorlab/R10_ZERO_POSTER_RECOVERY_20261029.txt',
 'cloudflare/music-intelligence/test/turbo-r9-recovery-wiring.test.mjs',
 'app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt',
 'app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt',
 testRoot+'CoverR9BackgroundRecoveryTest.kt',testRoot+'CoverR9EvidenceRecoveryTest.kt',testRoot+'CoverR9SnapshotRecoveryTest.kt',
 testRoot+'CoverLab80RoundClosureTest.kt',
}
changed=set(subprocess.check_output(['git','diff','--name-only',base],text=True).splitlines())
assert changed<=allowed,changed-allowed
subprocess.run(['git','diff','--check',base],check=True)
p=root+'DiscogsDirectVersionBrowser.kt'
a=subprocess.check_output(['git','show',base+':'+p],text=True); b=Path(p).read_text()
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
ui_changes = [
    ("            // Show the continuation even if loading never clears with zero cards.\n            if (isTurbo && mode == DiscogsDirectMode.COVER && activeCriteria != null) {", "            if (isTurbo && mode == DiscogsDirectMode.COVER && activeCriteria != null &&\n                (!loading || publishedCoverSnapshots.isNotEmpty() || previousRoundRows.isNotEmpty())) {"),
    ("                            onClick = {\n                                if (presentationStatus.manualContinue) {\n                                    session.presentation.continueEmptyWindow()\n                                    presentationStatus = session.presentation.status()\n                                    roundPlanComplete = presentationStatus.green\n                                    session.roundPlanComplete = roundPlanComplete\n                                } else {\n                                    runSearch(forceRefresh = true, appendRound = true)\n                                }\n                            },\n                            enabled = presentationStatus.green || presentationStatus.ready > 0 ||\n                                presentationStatus.manualContinue || presentationStatus.retryRequired,", "                            onClick = { runSearch(forceRefresh = true, appendRound = true) },\n                            enabled = presentationStatus.green || presentationStatus.ready > 0 || presentationStatus.retryRequired,"),
    ("                                presentationStatus.manualContinue -> \"Continua tornata · ciclo ${presentationStatus.nextCycle}\"\n                                preparationAudioPaused -> context.getString(com.metrolist.music.R.string.musiclab_cover_wait_audio)", "                                preparationAudioPaused -> context.getString(com.metrolist.music.R.string.musiclab_cover_wait_audio)")
]
for newer, older in ui_changes:
    assert b.count(newer) == 1, 'R10 UI delta missing or repeated'
    b = b.replace(newer, older, 1)
assert a.rstrip()==b.rstrip(),'Player, UI/cards/header/footer or unrelated search changed'
for path in ['app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt',
 'app/src/main/res/values/metrolist_strings.xml']:
 assert subprocess.check_output(['git','show',base+':'+path],text=True).rstrip()==Path(path).read_text().rstrip(),path
print('PASS: R9 scope, native player/UI/cards, other modes and resources preserved')


assert subprocess.check_output(['git','show',base+':'+root+'TitleMeaningResolver.kt'],text=True)==Path(root+'TitleMeaningResolver.kt').read_text()
