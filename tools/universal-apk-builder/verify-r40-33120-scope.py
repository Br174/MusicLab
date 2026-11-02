#!/usr/bin/env python3
"""Scudo360 R40 strict isolation: user-accepted R39 broad Compilation search intact."""
from pathlib import Path
import subprocess

BASE="f0a445721dd52abdd3fbf13914e4992b962f6faa"
EXPECTED={
 "app/src/main/kotlin/com/metrolist/music/playback/MusicLabListPositionStore.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabTrackRecovery.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/test/kotlin/com/metrolist/music/playback/MusicLabR40SectionPolicyTest.kt",
 "tools/universal-apk-builder/prepare-turbo-r40-33120-env.py",
 "tools/universal-apk-builder/verify-r40-33120-scope.py",
 ".github/workflows/musiclab-r40-context-33120-apk.yml",
}
PROTECTED=[
 "app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationSearchSessionPolicy.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED, "STOP unapproved R40 paths: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for p in PROTECTED:
    assert git("rev-parse",BASE+":"+p)==git("rev-parse","HEAD:"+p), "STOP protected source changed: "+p
comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
oldComp=subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"],text=True)
def loading(s): return s.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
assert loading(oldComp)==loading(comp), "STOP: R39 Compilation query or admission changed"
assert 'query = criteria.query' in comp and 'perPage = if (criteria.language == null) 100 else 25' in comp
hit=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
oldHit=subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"],text=True)
def chartParse(s):return s.split("internal fun parseSongs(",1)[1].split("@OptIn(ExperimentalMaterial3Api::class)",1)[0]
assert chartParse(oldHit)==chartParse(hit),"STOP historical Hit Parade source extraction changed"
player=Path("app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt").read_text()
context=Path("app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt").read_text()
thumb=Path("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt").read_text()
assert "MusicLabSectionPlayback.skip(this, 1)" in player
assert player.count("MusicLabSectionPlayback.skip(this, -1)") == 2
assert "connection.playQueue(ListQueue(title = source, items = listOf(item), startIndex = 0, position = 0L))" in context
assert "fun externalSelection()" in context
assert "MusicLabSectionPlayback.isCurrent(nativeMediaMetadata?.id)" in thumb
assert "MusicLabTrackRecovery.find(" in hit
assert "val selectedReleaseId" not in comp or "selectedReleaseId" in comp
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R40 SCOPE PASS] R39 Compilation query and protected Cover/audio engine identical; context/selection and fallback contracts present")
