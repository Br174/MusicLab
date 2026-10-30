#!/usr/bin/env python3
"""Video First change guard: preserve Turbo active source and protected player/UI."""
from pathlib import Path
import subprocess
BASE="b35cc61c18daca9d4752fac201dcacf28a35015f"
ROOT="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
 ROOT+"CoverDiscoverySources.kt",
 "tools/universal-apk-builder/prepare-turbo-video-first-env.py",
 "tools/universal-apk-builder/verify-turbo-video-first-scope.py",
 ".github/workflows/musiclab-turbo-video-first-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed<=ALLOWED, f"Unexpected modifications: {sorted(changed-ALLOWED)}"
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
source=Path(ROOT+"CoverDiscoverySources.kt").read_text()
assert "val deepRound = if (turbo)" in source
assert "discoverYouTubeCoverRound(cleanTitle, videoRound)" in source
assert "deepRound?.await()" in source
for protected in ("DiscogsDirectVersionBrowser.kt","CoverPreparationPolicy.kt","CoverPriorityOrder.kt","CoverLabTurboRoundAdmission.kt","CoverRoundSearchPlan.kt","CoverOriginalResolver.kt"):
 p=ROOT+protected
 old=subprocess.check_output(["git","show",BASE+":"+p],text=True)
 assert Path(p).read_text()==old, f"Protected source changed: {p}"
print("Video First guard PASS: only source lane and build controls changed; player/UI/priority preserved")
