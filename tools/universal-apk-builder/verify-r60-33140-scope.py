#!/usr/bin/env python3
"""MusicLab R60 change boundary, based on R59 signed source."""
from pathlib import Path
import subprocess

BASE = "71ae829d51058135c41683cdc9402ed600556c2e"
prefix = "app/src/main/kotlin/com/metrolist/music/"
allowed = {
  ".github/workflows/musiclab-r60-cover-compilation-audio-latency-33140-apk.yml",
  prefix + "playback/MusicService.kt",
  prefix + "discogs/CompilationTrackResolver.kt",
  prefix + "ui/component/CompilationScreen.kt",
  prefix + "ui/component/DiscogsDirectVersionBrowser.kt",
  "cloudflare/music-intelligence/test/musiclab-r60-cover-compilation-audio-latency.test.mjs",
  "tools/universal-apk-builder/prepare-turbo-r60-33140-env.py",
  "tools/universal-apk-builder/verify-r60-33140-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args], text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed == allowed, "R60 file scope invalid: " + repr(sorted(changed.symmetric_difference(allowed)))
service=Path(prefix+"playback/MusicService.kt").read_text()
cover=Path(prefix+"ui/component/DiscogsDirectVersionBrowser.kt").read_text()
resolver=Path(prefix+"discogs/CompilationTrackResolver.kt").read_text()
assert "R60_STREAM_JOIN" in service
assert "R59_RESTORE_NO_DELAYED_SEEK" in service
assert "PLAYBACK_START_BUFFER_MS = 1_600" in service
assert "resolveTrackForPlayback" in resolver
assert "COVER_TAP_TO_PLAYER_MS=" in cover
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("R60 source and compatibility gates passed")
