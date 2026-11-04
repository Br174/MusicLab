#!/usr/bin/env python3
"""MusicLab R59 exact source guard; preserve R58 other-section behavior."""
from pathlib import Path
import subprocess

BASE = "121c964ea00820ac481477c0f21b3359dac71ba4"
P = "app/src/main/kotlin/com/metrolist/music/"
EXPECTED = {
    ".github/workflows/musiclab-r59-global-audio-antirestart-33139-apk.yml",
    P + "playback/MusicService.kt",
    P + "playback/PlaybackSprintPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/PlaybackSprintPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r59-global-audio-antirestart.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r59-33139-env.py",
    "tools/universal-apk-builder/verify-r59-33139-scope.py",
}
def git(*argv):
    return subprocess.check_output(["git",*argv],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual = set(filter(None, git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual == EXPECTED, "R59 changed-file scope mismatch: " + repr(sorted(actual.symmetric_difference(EXPECTED)))
for protected in (
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/StreamUrlCache.kt",
    P+"utils/InnerTubeXPlayer.kt",
    P+"utils/potoken/PoTokenGenerator.kt",
    P+"ui/component/CompilationScreen.kt",
    P+"ui/component/CompilationLightSearchPolicy.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    P+"discogs/DiscogsClient.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected), "Protected code altered: "+protected
s=Path(P+"playback/MusicService.kt").read_text()
start=s.index("val playerStateFile =")
end=s.index("// Single background consumer",start)
assert "delay(1000)" not in s[start:end]
assert "player.seekTo(playerState.currentMediaItemIndex" not in s[start:end]
shuffle=s[s.index("override fun onShuffleModeEnabledChanged"):s.index("override fun onRepeatModeChanged")]
assert "player.setMediaItems(" not in shuffle and "player.prepare()" not in shuffle
assert "mayRestorePersistedQueue(" in s and "mayApplyAsyncShuffle(" in s
assert "R59_LOCAL_AUDIO_FASTPATH" in s
assert 'PLAYBACK_START_BUFFER_MS = 1_600' in s
assert 'PLAYBACK_REBUFFER_MS = 8_000' in s
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R59] audio-only source diff and no delayed seek/reset; other sections protected")
