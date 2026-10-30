#!/usr/bin/env python3
"""R14 exact-parent scope and invariant guard. Never touch the production family."""
from pathlib import Path
from copy import deepcopy
import json
import subprocess

BASE="4629bc7db1da71b4d503f4b1cd60bfe196c0f27e"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
    ROOT+"CoverRoundPresentation.kt",
    ROOT+"DiscogsDirectVersionBrowser.kt",
    ROOT+"CoverDiscoverySources.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR14CycleBatchTest.kt",
    "cloudflare/music-intelligence/test/turbo-r14-source-cycle-contract.test.mjs",
    "behaviors/musiclab-cover.json",
    ".github/workflows/musiclab-turbo-r14-cycle-source-apk.yml",
    "tools/universal-apk-builder/verify-r14-cycle-source-scope.py",
    "tools/universal-apk-builder/prepare-turbo-r14-cycle-source-env.py",
    ".motorlab/R14_CYCLE_SOURCE_THROUGHPUT_CHECKPOINT_20261030.txt",
}
def base(path):
    return subprocess.check_output(["git","show",BASE+":"+path],text=True)
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed <= ALLOWED, f"Unapproved modifications: {changed-ALLOWED}"
subprocess.run(["git","diff","--check",BASE],check=True)

# Modifications to the original native player/UI or to other music-mode search
# are forbidden. Only the existing Turbo runSearch orchestrator can change.
path=ROOT+"DiscogsDirectVersionBrowser.kt"
before=base(path)
after=Path(path).read_text()
start="    fun runSearch("
end="        // A further Cover round never clears or reorders already committed"
def outside(s):
    assert s.count(start)==1 and s.count(end)==1
    a,tail=s.split(start,1)
    _,z=tail.split(end,1)
    return a+start+end+z
assert outside(before)==outside(after),"Player, UI or unrelated modes changed"

# R14 can change the cycle buffer, but not its capacity.
p=Path(ROOT+"CoverRoundPresentation.kt").read_text()
for required in (
    "const val INITIAL_SIZE = 10", "const val BLOCK_SIZE = 20",
    "const val ROUND_SIZE = 50", "fun takeTimedCycle(): List<T>",
    "state.windowPublished = true", "state.manualWindowSkips",
):
    assert required in p, required
assert "appendVisible(presentation.takeTimedCycle())" in after
assert "if (linkingJob?.isActive == true)" in after
assert "delay(55L)" in after

# Prove that no permitted music source except explicitly shelved SHS was removed.
source=Path(ROOT+"CoverDiscoverySources.kt").read_text()
for required in (
    'val coverInfo = async(Dispatchers.IO)',
    'val musicBrainzLookup =',
    'cachedLane("Apple/iTunes")',
    'cachedLane("Spotify")',
    'cachedLane("Last.fm")',
    'cachedLane("LRCLIB")',
    'onSourceCompleted(lane.first, lane.second, year)',
    'if (turbo) {',
    '"Standby: fonte non interrogata"',
):
    assert required in source, required
for required in (
    'val discogs = discogsProducer?.takeIf { it.isActive }',
    'val youtube = launch {',
    'CoverPriorityOrder.bindDocumentedVideo(raw, pending.snapshot())',
):
    assert required in after, required

new=json.loads(Path("behaviors/musiclab-cover.json").read_text())
old=json.loads(base("behaviors/musiclab-cover.json"))
change=new.pop("approvedR14CyclesAndSources")
assert change["cycleCaps"]==[10,20,20]
assert new["approvedNextSpecification"].pop("cyclePublicationOverrideR14")
assert new==old, "Unexpected unrelated behavior memory change"

# Protect documentary/evidence/credit logic, original identification, recording
# duplicates, audio and package identity. R14 changes only cycle/source orchestration.
for filename in (
    "CoverLabTurboRoundAdmission.kt", "CoverPriorityOrder.kt",
    "CoverLab71FoundationGate.kt", "CoverOriginalResolver.kt",
    "CoverLabTurboTwinGuard.kt", "TitleMeaningResolver.kt",
    "CoverRoundSearchPlan.kt", "CoverPreparationPolicy.kt",
    "MusicBrainzCoverSource.kt", "DiscogsVersionSource.kt",
):
    path=ROOT+filename
    assert Path(path).read_text()==base(path),path
print("R14 scope: PASS; same 10+20+20 maximum, preserved evidence, signer, player, source coverage.")
