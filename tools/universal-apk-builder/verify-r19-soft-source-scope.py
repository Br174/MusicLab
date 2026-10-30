#!/usr/bin/env python3
"""MusicLab R19: pure ordering and optional verified local-archive label."""
from pathlib import Path
import subprocess
BASE="581a1a41916f84670e9f0f0fccb9406172ca4af8"
allowed={
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt",
"app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab77PriorityQueueTest.kt",
"app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab81SixRoundTest.kt",
"app/src/test/kotlin/com/metrolist/music/ui/component/CoverLab82PacketPriorityTest.kt",
"app/src/test/kotlin/com/metrolist/music/ui/component/CoverR19SourcePriorityTest.kt",
"cloudflare/music-intelligence/test/turbo-r19-soft-source-priority.test.mjs",
"tools/universal-apk-builder/verify-r19-soft-source-scope.py",
"tools/universal-apk-builder/prepare-turbo-r19-source-priority-env.py",
".github/workflows/musiclab-turbo-r19-source-priority-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed, f"R19 unauthorized files: {changes-allowed}"
subprocess.run(["git","diff","--check",BASE],check=True)
def same(path):
 current=Path(path).read_text()
 base=subprocess.check_output(["git","show",BASE+":"+path],text=True)
 assert current==base,f"Protected MusicLab R18 file modified: {path}"
for path in [
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundSearchPlan.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverFilterIdentity.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLab71FoundationGate.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboTwinGuard.kt",
"app/build.gradle.kts",
"behaviors/musiclab-cover.json",
]: same(path)
priority=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt").read_text()
assert "private fun musicalSourceTier" in priority
assert "fun isPublished(row: DiscogsVersionSeed)" in priority
assert "fun retainedRows(" in priority and "fun bindDocumentedVideo(" in priority
assert "fun minimumRound(" in Path("app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt").read_text()
print("R19 SCOPE PASS: published R18 behavior, admission, Cover filters, audio, discovery and 10+20+20 preserved")
