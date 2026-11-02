#!/usr/bin/env python3
"""MotorLab MusicLab R44: all-section standby/resume without player mutation."""
from pathlib import Path
import subprocess

BASE = "53f8358a0236b9a30b7d0697280bc1426b954bdc"
EXPECTED = {
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionWorkCheckpoint.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionWorkPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationResolvedCache.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/viewmodels/MusicLabOnlineSearchSnapshot.kt",
    "app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt",
    "app/src/test/kotlin/com/metrolist/music/playback/MusicLabSectionWorkPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r44-universal-standby-resume.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r41-section-memory.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r44-33124-env.py",
    "tools/universal-apk-builder/verify-r44-33124-scope.py",
    ".github/workflows/musiclab-r44-universal-standby-33124-apk.yml",
}
PROTECTED = [
    "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionMemory.kt",
    ".motorlab/active-family.json",
    "tools/universal-apk-builder/build-apk.sh",
]
def git(*args): return subprocess.check_output(["git", *args], text=True).strip()
subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
changed = set(git("diff", "--name-only", BASE, "HEAD").splitlines())
assert changed == EXPECTED, "Unexpected R44 files: " + repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in PROTECTED:
    assert git("rev-parse", BASE+":"+path) == git("rev-parse", "HEAD:"+path), path
base_comp = subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"], text=True)
comp = Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
def compilation_query(s): return s.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
# R44 may save newly completed pages. Discogs q-mode and admission remain untouched.
assert 'query = criteria.query' in comp
assert 'perPage = if (criteria.language == null) 100 else 25' in comp
assert 'query = criteria.query' in base_comp
assert "MusicLabCompilationResolvedCache.load(context, current)" in comp
assert "MusicLabSectionWorkPolicy.shouldResumeInterrupted" in comp
assert "Riproduci tutto per brani" not in comp
hit = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
chart = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt").read_text()
search = Path("app/src/main/kotlin/com/metrolist/music/viewmodels/OnlineSearchViewModel.kt").read_text()
assert "MusicLabSectionWorkCheckpoint.complete" in hit and "MusicLabSectionWorkCheckpoint.complete" in chart
assert "setSectionActive(active: Boolean)" in search
assert "MusicLabOnlineSearchSnapshot.loadSummary" in search
assert "MusicLabOnlineSearchSnapshot.loadPage" in search
assert "MusicLabOnlineSearchSnapshot.savePage" in search
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[R44 SCUDO360] isolated, native Cover preserved, search metadata cached, signed playback sources protected")
