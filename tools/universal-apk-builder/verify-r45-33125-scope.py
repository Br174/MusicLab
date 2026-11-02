#!/usr/bin/env python3
"""R45 Change Compatibility Guard: exact approved source delta from successful R44."""
from pathlib import Path
import subprocess

BASE = "a5185106928ebd455862f248e51cb8ddd1b9ba19"
EXPECTED = {
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabManualVersionStore.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/YouTubeMatchDialog.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationFallbackQueryPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r45-manual-version-scroll-compilation.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r42-audio-seek-selection.test.mjs",
    ".github/workflows/musiclab-r45-manual-version-scroll-compilation-33125-apk.yml",
    "tools/universal-apk-builder/prepare-turbo-r45-33125-env.py",
    "tools/universal-apk-builder/verify-r45-33125-scope.py",
}
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed = set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed == EXPECTED, "Unauthorized source scope: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in [
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r44-universal-standby-33124-apk.yml",
]:
    assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path), path
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
code = Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
assert "query = criteria.query" in code
assert 'perPage = if (criteria.language == null) 100 else 25' in code
assert "CompilationFallbackQueryPolicy.query" in code
assert "trackTitle = null, trackArtist = null" in code
assert "Riproduci tutto per brani" not in code
print("[R45 SCUDO] isolated R44 descendant, approved changes only, native player preserved")
