#!/usr/bin/env python3
"""R16 isolated change guard against signed R15 source."""
from pathlib import Path
import subprocess
BASE="b35cc61c18daca9d4752fac201dcacf28a35015f"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
    ROOT+"DiscogsDirectVersionBrowser.kt",
    ROOT+"CoverPriorityOrder.kt",
    ROOT+"CoverR16FlexibleVideoMatch.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR16FlexibleVideoMatchTest.kt",
    "cloudflare/music-intelligence/test/turbo-r16-flexible-video-metadata.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r16-flexible-video-env.py",
    "tools/universal-apk-builder/verify-r16-flexible-video-scope.py",
    ".github/workflows/musiclab-turbo-r16-flexible-video-apk.yml",
    ".motorlab/R16_FLEXIBLE_VIDEO_METADATA_20261030.txt",
}
def run(*args):
    return subprocess.check_output(args,text=True)
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
diff=set(run("git","diff","--name-only",BASE).splitlines())
assert diff <= ALLOWED, "Unapproved LAB files: "+str(diff-ALLOWED)
subprocess.run(["git","diff","--check",BASE],check=True)
browser=Path(ROOT+"DiscogsDirectVersionBrowser.kt").read_text()
assert "CoverR16FlexibleVideoMatch.evaluate(candidate" in browser
assert "CoverR16FlexibleVideoMatch.queries(next)" in browser
assert "YouTube.queue(videoIds = ids)" in browser
for file in (
    ROOT+"CoverLabTurboRoundAdmission.kt",
    ROOT+"CoverLab71FoundationGate.kt",
    ROOT+"CoverRoundPresentation.kt",
    ROOT+"CoverLabTurboTwinGuard.kt",
    ROOT+"CoverRoundSearchPlan.kt",
    ROOT+"TitleMeaningResolver.kt",
    "behaviors/musiclab-cover.json",
):
    assert Path(file).read_text()==run("git","show",BASE+":"+file), "Protected file changed: "+file
print("R16_SCOPE_PASS: signed R15 ancestor and only isolated LAB metadata files altered")
