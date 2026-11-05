#!/usr/bin/env python3
"""R67 isolated audio-first gate based on signed, Scudo360-successful R66."""
from pathlib import Path
import subprocess

BASE="0c5fffe9147d56ca5a996cc1eba36bf29c2add02"
P="app/src/main/kotlin/com/metrolist/music/"
CHANGED={
    P+"playback/MusicLabArtworkAudioGate.kt",
    P+"playback/MusicService.kt",
    P+"ui/screens/search/MusicLabLightArtwork.kt",
    P+"ui/screens/search/MusicLabChartArtwork.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    P+"ui/component/MusicLabFastPath.kt",
    P+"ui/component/CompilationScreen.kt",
    P+"ui/component/CoverVisibleYearResolver.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/MusicLabArtworkAudioGateTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r67-audio-first-artwork.test.mjs",
    "cloudflare/music-intelligence/test/turbo-r27-artwork-coverage-playback.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r67-33147-env.py",
    "tools/universal-apk-builder/verify-r67-audio-first-scope.py",
    ".github/workflows/musiclab-r67-audio-first-33147-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual==CHANGED, "Unexpected or missing R67 changes: "+repr(sorted(actual ^ CHANGED))
for preserved in (
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/PlaybackSprintPolicy.kt",
    P+"playback/MusicLabSwipeInputPolicy.kt",
    P+"ui/player/Player.kt",
    P+"ui/player/MiniPlayer.kt",
    P+"viewmodels/OnlineSearchViewModel.kt",
    P+"ui/component/DiscogsDirectVersionBrowser.kt",
    P+"ui/component/CoverVisibleSortPolicy.kt",
    P+"ui/component/CoverRoundSearchPlan.kt",
    P+"ui/component/CoverTitleTypography.kt",
    P+"ui/screens/search/MusicLabHistoricHtmlDecoder.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    P+"discogs/DiscogsClient.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+preserved)==git("rev-parse","HEAD:"+preserved), "Protected: "+preserved
service=Path(P+"playback/MusicService.kt").read_text()
gate=Path(P+"playback/MusicLabArtworkAudioGate.kt").read_text()
assert 'MusicLabArtworkAudioGate.onAudioRequested()' in service
assert 'MusicLabArtworkAudioGate.onPlayerState(playbackState, player.playWhenReady)' in service
assert 'private fun playSectionEffective(item: MediaItem, sectionTitle: String)' in service
assert 'private fun playNowEffective(item: MediaItem)' in service
assert 'private val artworkNetworkSlots = Semaphore(2)' in gate
assert 'pending.forEach { it.cancel(' in gate
assert 'player.prepare()' in service
assert 'UAB_UPDATE_FAMILY_VERSION_CODE' in Path("tools/universal-apk-builder/prepare-turbo-r67-33147-env.py").read_text()
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R67] Only optional artwork networking and read-only audio state hooks changed; player/Unicode/cover ranking safe")
