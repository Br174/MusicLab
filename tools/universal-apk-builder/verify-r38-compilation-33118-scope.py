#!/usr/bin/env python3
"""R38 isolated Compilation session/credentials/history patch contract."""
from pathlib import Path
import subprocess

BASE = "2d8b9d61f9cb0430c25927fdf1cf9bfca0344aca"
EXPECTED = {
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationSearchTitlePolicyTest.kt",
    "tools/universal-apk-builder/prepare-turbo-r38-session-33118-env.py",
    "tools/universal-apk-builder/verify-r38-compilation-33118-scope.py",
    ".github/workflows/musiclab-r38-compilation-session-33118-apk.yml",
}
PROTECTED = [
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
    "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicy.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
    ".motorlab/active-family.json",
    "tools/universal-apk-builder/build-apk.sh",
]
subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
changed = set(subprocess.check_output(["git", "diff", "--name-only", BASE, "HEAD"], text=True).splitlines())
assert changed == EXPECTED, "STOP WRONG SCOPE: " + repr(sorted(changed.symmetric_difference(EXPECTED)))
for path in PROTECTED:
    before = subprocess.check_output(["git", "rev-parse", BASE+":"+path], text=True).strip()
    after = subprocess.check_output(["git", "rev-parse", "HEAD:"+path], text=True).strip()
    assert before == after, "STOP protected MusicLab feature mutated: " + path

screen = Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
policy = Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt").read_text()
tests = Path("app/src/test/kotlin/com/metrolist/music/ui/component/CompilationSearchTitlePolicyTest.kt").read_text()
assert "context.dataStore.data.first()[DiscogsTokenKey]" in screen
assert screen.index("searchGeneration += 1") < screen.index("context.dataStore.data.first()[DiscogsTokenKey]")
assert "CompilationSessionStore.resetForNewSearch()" in screen
assert "activeSearch = null" in screen
assert "if (generation != searchGeneration)" in screen
assert "CompilationSearchHistoryStore.save(context, criteria)" in screen
assert 'R.drawable.history' in screen
assert "trackRequest = null" in screen
assert "runSearch(previous)" in screen
assert "CompilationSearchTitlePolicy.clean(target.title)" in screen
assert "trackTitle = cleanTitle, trackArtist = target.artist.trim()" in screen
assert "private const val LIMIT = 30" in policy
assert 'stringPreferencesKey("musiclab_compilation_search_history_r38")' in policy
assert '"L\'anno che verrà (Remastered in 192 KHz)"' in tests
assert "onClick = ::runSearch" not in screen
assert "fun runSearch(override:" not in screen
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[R38 CONTRACT PASS] Search isolation, token gate, 30-entry history, clean titles, protected modules")
