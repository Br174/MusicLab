#!/usr/bin/env python3
"""Exact-parent R13 protection for documentary-to-video joining."""
from pathlib import Path
import subprocess
BASE="38d5dd778dd409b4e2a85a9ddbacb010bb4056ea"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
    ROOT+"CoverPriorityOrder.kt",
    ROOT+"DiscogsDirectVersionBrowser.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR13DocumentaryVideoJoinTest.kt",
    ".github/workflows/musiclab-turbo-r13-documentary-join-apk.yml",
    "tools/universal-apk-builder/verify-r13-documentary-join-scope.py",
    "tools/universal-apk-builder/prepare-turbo-r13-documentary-join-env.py",
    ".motorlab/R13_DOCUMENTARY_VIDEO_JOIN_CHECKPOINT_20261030.txt",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
diff=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert diff<=ALLOWED,diff-ALLOWED
subprocess.run(["git","diff","--check",BASE],check=True)
def old(path):
    return subprocess.check_output(["git","show",BASE+":"+path],text=True)
p=ROOT+"CoverPriorityOrder.kt"
newPriority=Path(p).read_text()
helper="    /** Reconcile an independently discovered YouTube result with an already\n     *  DOCUMENTED work and the SAME credited performer. This establishes only\n     *  the work link, never the identity of a particular discographic recording.\n     *  Title coincidence alone cannot corroborate a work or an interpreter.\n     */\n    fun bindDocumentedVideo(\n        raw: CoverSourceCandidate, documentaryLeads: List<CoverSourceCandidate>,\n    ): CoverSourceCandidate {\n        if (raw.playbackVideoId.isNullOrBlank()) return raw\n        for (lead in documentaryLeads) {\n            if (!lead.workRelationConfirmed || !lead.sources.any {\n                it.equals(\"COVER.INFO\", true) || it.equals(\"MusicBrainz\", true)\n            }) continue\n            if (CoverLab71FoundationGate.isForbidden(\n                    lead.title, lead.artist, raw.playbackVideoTitle.orEmpty(), raw.artist,\n                )) continue\n            val linked = bindVideo(raw, lead)\n            if (linked !== raw) {\n                // A verified performer+work match does NOT mean the video is the\n                // FIRST publishing of that recording. Only the identical native\n                // video reference can retain the first-publication assertion.\n                return linked.copy(firstPublicationConfirmed =\n                    lead.firstPublicationConfirmed && lead.playbackVideoId == raw.playbackVideoId)\n            }\n        }\n        return raw\n    }\n\n"
assert newPriority.count(helper)==1,"Helper missing or duplicated"
assert newPriority.replace(helper,"",1)==old(p),"Unexpected priority/evidence/category change"
b=ROOT+"DiscogsDirectVersionBrowser.kt"
before=old(b)
after=Path(b).read_text()
start="    fun runSearch("
end="        // A further Cover round never clears or reorders already committed"
def erase(s):
    assert s.count(start)==1 and s.count(end)==1
    a,rest=s.split(start,1)
    _,z=rest.split(end,1)
    return a+start+end+z
assert erase(before)==erase(after),"Unrelated player, UI, search family or audio changed"
assert after.count("CoverPriorityOrder.bindDocumentedVideo(raw, pending.snapshot())")==2
assert after.count("CoverPriorityOrder.bindDocumentedVideo(raw, listOf(next))")==1
for f in ("CoverRoundPresentation.kt","CoverLabTurboRoundAdmission.kt","CoverDiscoverySources.kt",
          "TitleMeaningResolver.kt","CoverLabTurboTwinGuard.kt","CoverPreparationPolicy.kt","CoverOriginalResolver.kt"):
    name=ROOT+f
    assert Path(name).read_text()==old(name),name
print("R13 verified: only documentary video linking changed; 3/2/1, 10+20+20, player and current Scudo preserved")
