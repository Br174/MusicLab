#!/usr/bin/env python3
"""R18 is a filter-only LAB from Bruno's device-accepted R17. Never mutate CANDIDATA."""
import subprocess
from pathlib import Path
BASE="82970f87442ac14035533f48b31043fcaefc305a"
allowed={
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverFilterIdentity.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverFilterIdentityTest.kt",
 "cloudflare/music-intelligence/test/turbo-r18-category-only.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r18-cover-filters-env.py",
 "tools/universal-apk-builder/verify-r18-filter-scope.py",
 ".github/workflows/musiclab-turbo-r18-cover-filters-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, f"Unexpected R18 source changes: {changed-allowed}"
subprocess.run(["git","diff","--check",BASE],check=True)
def same(path):
 old=subprocess.check_output(["git","show",BASE+":"+path],text=True)
 assert Path(path).read_text()==old, "Protected R17 source changed: "+path
for f in [
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverLabTurboRoundAdmission.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverDiscoverySources.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverCardMetadata.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundPresentation.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
 "app/build.gradle.kts",
 "behaviors/musiclab-cover.json",
]: same(f)
p=Path("app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt").read_text()
assert ".filter { directMatchesCategory(it, category, isTurbo) }" in p
assert "useTurboTags = isTurbo && mode == DiscogsDirectMode.COVER" in p
assert "val unifiedCoverDisplay = remember(" in p
print("R18 FILTER-ONLY SCOPE PASS; R17 source, candidate, audio, ranking, work gates protected")
