#!/usr/bin/env python3
"""R26 exact-source guard: only candidate/published ordering, no admission or audio."""
import subprocess
BASE="c5cbf6d8f2c7c79f634d675c29e9e8d76d7d48d3"
allowed={
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR19SourcePriorityTest.kt",
 "app/src/test/kotlin/com/metrolist/music/ui/component/CoverR26SourcePositionOnlyTest.kt",
 "cloudflare/music-intelligence/test/turbo-r26-source-position-only.test.mjs",
 "tools/universal-apk-builder/prepare-turbo-r26-source-order-only-env.py",
 "tools/universal-apk-builder/verify-r26-source-order-only-scope.py",
 ".github/workflows/musiclab-turbo-r26-source-order-only-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changed<=allowed, "Unexpected R26 modifications: "+str(sorted(changed-allowed))
assert "app/src/main/kotlin/com/metrolist/music/ui/component/CoverPriorityOrder.kt" in changed
assert "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt" in changed
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R26 SCOPE PASS] only priority and source display; audio, search admission, rounds preserved")
