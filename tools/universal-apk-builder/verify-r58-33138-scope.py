#!/usr/bin/env python3
"""MotorLab R58: exact delta from tested R57; reject changes to other sections."""
import subprocess
from pathlib import Path

BASE = "b17409a7eb478a9851120791ab54ae8ffd4ac471"
P = "app/src/main/kotlin/com/metrolist/music/"
EXPECTED = {
    ".github/workflows/musiclab-r58-global-audio-immediate-33138-apk.yml",
    P+"playback/MusicService.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    P+"ui/component/CompilationScreen.kt",
    "cloudflare/music-intelligence/test/musiclab-r58-global-audio-first.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r58-33138-env.py",
    "tools/universal-apk-builder/verify-r58-33138-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args], text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor", BASE, "HEAD"], check=True)
actual = set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual == EXPECTED, "R58 source scope mismatch: " + repr(sorted(actual.symmetric_difference(EXPECTED)))
for file in (
    P+"playback/PlaybackSprintPolicy.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/StreamUrlCache.kt",
    P+"utils/InnerTubeXPlayer.kt",
    P+"utils/potoken/PoTokenGenerator.kt",
    P+"ui/component/MusicLabFastPath.kt",
    P+"ui/component/CompilationLightSearchPolicy.kt",
    P+"ui/component/MusicLabCompilationSnapshot.kt",
    P+"discogs/DiscogsClient.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+file)==git("rev-parse","HEAD:"+file), "R58 protected file changed: "+file
service=Path(P+"playback/MusicService.kt").read_text()
ui=Path(P+"ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
disc=Path(P+"ui/component/CompilationScreen.kt").read_text()
assert service.index('if (mediaId.startsWith("local:"))') < service.index("val storedFormat = runBlocking(Dispatchers.IO)")
assert 'R58_IS_PLAYING' in service
assert ui.index('beginPlaybackPriorityBurst("hit-parade-tap")') < ui.index("MusicLabHitParadeVideoResolver.find(")
assert 'rememberedPlayableMediaItem' in ui
assert 'verificationJob?.cancel()' in disc and 'val unverified = results.filter' in disc
assert "player.seekTo(0L)" in service and "player.prepare()" in service
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R58] exact source scope, protected playback behavior and previous R57 fully retained")
