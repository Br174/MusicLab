#!/usr/bin/env python3
"""R66: isolate HPI encoding / Cover typography above signed R65 LAST_GOOD."""
from pathlib import Path
import subprocess

BASE="d5c5f9a57144b09cb9bb4ba01b647c919504454b"
S="app/src/main/kotlin/com/metrolist/music/ui/screens/search/"
C="app/src/main/kotlin/com/metrolist/music/ui/component/"
ALLOWED={
    S+"MusicLabHistoricHtmlDecoder.kt",
    S+"MusicLabHistoricalHitParade.kt",
    S+"MusicLabHistoricalExtraCharts.kt",
    S+"MusicLabEditorialSnapshot.kt",
    C+"CoverTitleTypography.kt",
    C+"CoverRoundSearchPlan.kt",
    C+"CoverPriorityOrder.kt",
    C+"DiscogsVersionSource.kt",
    C+"DiscogsDirectVersionBrowser.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricHtmlDecoderTest.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CoverTitleTypographyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r66-hpi-unicode.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r66-33146-env.py",
    "tools/universal-apk-builder/verify-r66-unicode-scope.py",
    ".github/workflows/musiclab-r66-unicode-33146-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert changed==ALLOWED, "Unexpected R66 paths or missing tests: "+repr(sorted(changed ^ ALLOWED))
for protected in (
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlaybackSprintPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected),protected
week=Path(S+"MusicLabHistoricalHitParade.kt").read_text()
annual=Path(S+"MusicLabHistoricalExtraCharts.kt").read_text()
for source in (week,annual):
    assert 'MusicLabHistoricHtmlDecoder.decode(body.bytes(), response.header("Content-Type"))' in source
    assert 'response.body?.string()' not in source
assert 'CoverTitleTypography.shown(seed.trackTitle, referenceTitle.orEmpty())' in Path(C+"DiscogsDirectVersionBrowser.kt").read_text()
assert 'if (round >= finalRound)' in Path(C+"CoverRoundSearchPlan.kt").read_text()
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R66] Exact Unicode-only change; R65 filters, player, videos and signature untouched")
