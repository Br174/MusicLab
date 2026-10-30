#!/usr/bin/env python3
"""Exact-base R12 guard; only the already-approved native Cover source coordinator may change."""
from pathlib import Path
import subprocess
BASE="4a7d24440120e90bda1f95c4ea6f8eb121d04710"
ROOT='app/src/main/kotlin/com/metrolist/music/ui/component/'
ALLOWED={
 ROOT+'DiscogsDirectVersionBrowser.kt',
 ROOT+'CoverPreparationPolicy.kt',
 'app/src/test/kotlin/com/metrolist/music/ui/component/CoverR12VideoThroughputTest.kt',
 'tools/universal-apk-builder/verify-r10-cover-scope.py',
 'tools/universal-apk-builder/verify-r12-video-throughput-scope.py',
 'tools/universal-apk-builder/prepare-turbo-r12-video-throughput-env.py',
 '.github/workflows/musiclab-turbo-r10-empty-packet-apk.yml',
 '.github/workflows/musiclab-turbo-r12-video-throughput-apk.yml',
 '.motorlab/R12_VIDEO_THROUGHPUT_CHECKPOINT_20261030.txt',
}
subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
changed=set(subprocess.check_output(['git','diff','--name-only',BASE],text=True).splitlines())
assert changed<=ALLOWED,changed-ALLOWED
subprocess.run(['git','diff','--check',BASE],check=True)
def prior(path):
    return subprocess.check_output(['git','show',BASE+':'+path],text=True)
def without(s,a,b):
    assert s.count(a)==1,(a,s.count(a))
    h,t=s.split(a,1)
    assert t.count(b)>=1,(b,t.count(b))
    _,tail=t.split(b,1)
    return h+a+b+tail
browserPath=ROOT+'DiscogsDirectVersionBrowser.kt'
before=prior(browserPath)
after=Path(browserPath).read_text()
start='    fun runSearch('
end='        // A further Cover round never clears or reorders already committed'
assert without(before,start,end)==without(after,start,end), 'Unrelated player/UI modified'
oldPolicy=prior(ROOT+'CoverPreparationPolicy.kt')
newPolicy=Path(ROOT+'CoverPreparationPolicy.kt').read_text()
helper='    /** Scoped to each video ID; pass and audio limits are preserved. */\n    fun metadataAttemptAllowed(videoId: String, attemptedThisRound: Boolean): Boolean =\n        videoId.isNotBlank() && !attemptedThisRound\n\n'
assert newPolicy.count(helper)==1, 'Metadata helper missing'
assert newPolicy.replace(helper,'',1)==oldPolicy, 'Unrelated preparation policy modified'
assert 'metadata == null && sourceCooling("Metadati YouTube")' not in after
assert 'CoverPreparationPolicy.metadataAttemptAllowed(' in after
for file in ('CoverRoundPresentation.kt','CoverLabTurboRoundAdmission.kt','CoverPriorityOrder.kt','CoverDiscoverySources.kt','CoverOriginalResolver.kt','TitleMeaningResolver.kt'):
    p=ROOT+file
    assert prior(p)==Path(p).read_text(),p
print('PASS: R12 exact source 4a7, native engine/player/UI, evidence and 10+20+20 protected')
