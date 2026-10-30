#!/usr/bin/env python3
"""R23 does not change active R22 or established Cover/source families."""
import subprocess
BASE="21126d03af1a11f57d2a85069276c209fc0ba664"
allowed={
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "cloudflare/music-intelligence/test/turbo-r23-first-byte-diagnostic.test.mjs",
 "tools/universal-apk-builder/verify-r23-first-byte-scope.py",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changes=set(subprocess.check_output(["git","diff","--name-only",BASE],text=True).splitlines())
assert changes<=allowed, sorted(changes-allowed)
subprocess.run(["git","diff","--check",BASE],check=True)
print("[R23 SCOPE=PASS] isolated source-only diagnostic; no Cover or build changes")
