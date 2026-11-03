#!/usr/bin/env python3
"""R56 scoped player source-resolution improvements atop exact R55 baseline."""
from pathlib import Path
import subprocess

BASE="a47cf42d42f970739f7181bbd63467ca9f838a99"
P="app/src/main/kotlin/com/metrolist/music/"
EXPECTED={
    ".github/workflows/musiclab-r56-global-playback-priority-33136-apk.yml",
    P+"playback/MusicService.kt",
    P+"playback/PlaybackSprintPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r56-global-playback-priority.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r56-33136-env.py",
    "tools/universal-apk-builder/verify-r56-33136-scope.py",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual == EXPECTED, "R56 scope mismatch: "+repr(sorted(actual.symmetric_difference(EXPECTED)))
for untouched in (
    P+"ui/component/CompilationScreen.kt",
    P+"ui/component/CompilationLightSearchPolicy.kt",
    P+"ui/component/CoverSearchScreen.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"utils/InnerTubeXPlayer.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    P+"discogs/DiscogsClient.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+untouched)==git("rev-parse","HEAD:"+untouched), "R56 unauthorized diff "+untouched
service=Path(P+"playback/MusicService.kt").read_text()
assert 'beginPlaybackPriorityBurst("queue-preload-start")' in service
assert 'beginPlaybackPriorityBurst("queue-initial-start")' in service
assert 'beginPlaybackPriorityBurst("section-direct-start")' in service
assert 'beginPlaybackPriorityBurst("queue-auto-transition")' in service
assert 'songUrlCache[mediaId]?.let { cachedStream ->' in service
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R56] global audio service only, no UI/metadata/player identity/signing/Cover/HitParade mutation")
