#!/usr/bin/env python3
"""Scudo360 R42: exact authorized scope, no Compilation q-search regression."""
from pathlib import Path
import subprocess
BASE="e022530e77159231130d1f373e7ca962d312e309"
EXPECTED={
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabListPositionStore.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
"app/src/test/kotlin/com/metrolist/music/playback/MusicLabR42SectionSelectionTest.kt",
"cloudflare/music-intelligence/test/musiclab-r42-audio-seek-selection.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r42-33122-env.py",
"tools/universal-apk-builder/verify-r42-33122-scope.py",
".github/workflows/musiclab-r42-player-seek-selection-33122-apk.yml",
}
PROTECTED=[
"app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
"app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionMemory.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabPersistentLazyList.kt",
"app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt",
".motorlab/active-family.json",
"tools/universal-apk-builder/build-apk.sh",
]
def git(*a):return subprocess.check_output(["git",*a],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED,"Unexpected R42 files: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for p in PROTECTED:
    assert git("rev-parse",BASE+":"+p)==git("rev-parse","HEAD:"+p),p
def version(path,ref):
    if ref=="HEAD": return Path(path).read_text()
    return subprocess.check_output(["git","show",ref+":"+path],text=True)
comp="app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"
old,new=version(comp,BASE),version(comp,"HEAD")
def load(x):return x.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
assert load(old)==load(new),"STOP: R39 Discogs q-mode changed"
assert "query = criteria.query" in new and "perPage = if (criteria.language == null) 100 else 25" in new
hp="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"
before,after=version(hp,BASE),version(hp,"HEAD")
assert before.split("internal fun MusicLabHistoricalHitParadeScreen(",1)[0]==after.split("internal fun MusicLabHistoricalHitParadeScreen(",1)[0], "STOP historic chart fetch changed"
srv="app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt"
a,b=version(srv,BASE),version(srv,"HEAD")
method=b.split("    fun playSectionNow(item: MediaItem, sectionTitle: String) {",1)[1].split("    fun playNow(item: MediaItem)",1)[0]
assert "pendingQueuePreparation?.cancel()" in method
assert "player.setMediaItem(item, 0L)" in method
assert "player.playWhenReady = true" in method
assert "playSectionNow" not in a
assert a.split("    fun playNow(item: MediaItem)",1)[1]==b.split("    fun playNow(item: MediaItem)",1)[1],"STOP prior player implementation changed"
connection=version("app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt","HEAD")
section=version("app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt","HEAD")
assert "service.playSectionNow(item, sectionTitle)" in connection
assert "connection.playSectionNow(item, source)" in section
assert "connection.playQueue(ListQueue" not in section
assert "MusicLabListPositionStore.select(key, selected)" in section
player=version("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt","HEAD")
assert player.count("playerConnection.seekTo(it)") == 4
assert "LaunchedEffect(isCasting, mediaMetadata?.id)" in player
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R42 SCUDO360] protected R39 search, R41 persistence and Cover/player resolver unchanged; synchronous no-late-hydration section path")
