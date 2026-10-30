#!/usr/bin/env python3
"""R15 evidence-based root-cause change guard, pinned to signed R14."""
from pathlib import Path
import subprocess
import json

BASE="a3f2d6ebea442895eb2fe484bcd751f4f7afb239"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
 ROOT+"DiscogsDirectVersionBrowser.kt",
 ROOT+"CoverDiscoverySources.kt",
 ROOT+"CoverLabTurboRoundAdmission.kt",
 ROOT+"CoverPreparationPolicy.kt",
 ROOT+"CoverPriorityOrder.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR15RootCauseEvidenceTest.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab78PreparationPolicyTest.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR12VideoThroughputTest.kt",
 "cloudflare/music-intelligence/test/turbo-r15-real-root-cause.test.mjs",
 "behaviors/musiclab-cover.json",
 ".github/workflows/musiclab-r15-root-cause-red.yml",
 ".github/workflows/musiclab-turbo-r15-root-cause-apk.yml",
 "tools/universal-apk-builder/verify-r15-root-cause-scope.py",
 "tools/universal-apk-builder/prepare-turbo-r15-root-cause-env.py",
 ".motorlab/R15_ROOT_CAUSE_RECOVERY_20261030.txt",
}
def base(path):
    return subprocess.check_output(["git","show",BASE+":"+path],text=True)
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
diff=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert diff <= ALLOWED, f"Unapproved R15 changes {diff - ALLOWED}"
subprocess.run(["git","diff","--check",BASE],check=True)

browser_path=ROOT+"DiscogsDirectVersionBrowser.kt"
new_browser=Path(browser_path).read_text()
before_browser=base(browser_path)
extra="""    // R15: transient failures of a specific video must never suppress all
    // its retries throughout this round or poison unrelated video IDs.
    val metadataRetryAfter: CoverPreparationPolicy.MetadataRetry = CoverPreparationPolicy.MetadataRetry(),
"""
assert new_browser.count(extra)==1
normalized=new_browser.replace(extra,"",1)
start="    fun runSearch("
end="        // A further Cover round never clears or reorders already committed"
def unchanged_outside(s):
    assert s.count(start)==1 and s.count(end)==1
    left,rest=s.split(start,1)
    _,right=rest.split(end,1)
    return left+start+end+right
assert unchanged_outside(normalized)==unchanged_outside(before_browser),"Player, UI, unrelated search logic changed"
assert "session.metadataRetryAfter.failed(id)" in new_browser
assert "session.metadataRetryAfter.succeeded(id)" in new_browser
assert "session.searchedLeadRounds[CoverPriorityOrder.key(candidate)] = thisRound" not in (
    new_browser.split("suspend fun publish(candidate: CoverSourceCandidate) {")[1]
      .split("fun resolveOriginalWhenUseful()")[0]
)

source=Path(ROOT+"CoverDiscoverySources.kt").read_text()
assert "val songId = match.groupValues[1]" in source
assert "val songId = relationMatch?.groupValues" not in source
for lane in ('val coverInfo = async(Dispatchers.IO)',
             'val musicBrainzLookup =',
             'cachedLane("Apple/iTunes")',
             'cachedLane("Spotify")',
             'cachedLane("Last.fm")',
             'cachedLane("LRCLIB")',
             '"Standby: fonte non interrogata"'):
    assert lane in source, lane

policy=Path(ROOT+"CoverPreparationPolicy.kt").read_text()
assert "class MetadataRetry" in policy and "take(if (protectPlayback) 3" in policy
admission=Path(ROOT+"CoverLabTurboRoundAdmission.kt").read_text()
assert 'add("catalogue-credited-performance")' in admission
assert "matchedWorkWriter" in admission
priority=Path(ROOT+"CoverPriorityOrder.kt").read_text()
assert 'TitleMeaningResolver.canonical(title).endsWith(" $artist")' in priority

for file in (
 "CoverRoundPresentation.kt", "CoverLab71FoundationGate.kt",
 "CoverLabTurboTwinGuard.kt","CoverRoundSearchPlan.kt",
 "MusicBrainzCoverSource.kt","DiscogsVersionSource.kt",
 "TitleMeaningResolver.kt","CoverOriginalResolver.kt",
):
    path=ROOT+file
    assert Path(path).read_text()==base(path),f"Changed protected file {path}"
memory=json.loads(Path("behaviors/musiclab-cover.json").read_text())
old=json.loads(base("behaviors/musiclab-cover.json"))
r15=memory.pop("approvedR15RootCauseRecovery")
assert r15["protections"]["evidenceByRound"]==[3,2,2,2,2,1]
assert memory==old,"Changed existing approved behavior instead of appending R15"
print("R15 guard PASS: exact signed parent, source retention, old app UI/audio, round thresholds, 10-20-20 and R14 fallback preserved")
