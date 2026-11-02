#!/usr/bin/env python3
"""R41 Scudo360: hard protect R39 search and unchanged playback, exact source scope."""
import subprocess
from pathlib import Path

BASE="b8e14c465f425e4a84c518ea5980a32b6be9b736"
EXPECTED={
".github/workflows/musiclab-r41-section-memory-33121-apk.yml",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabListPositionStore.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabPersistentLazyList.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionMemory.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabCompilationSnapshot.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/AlbumScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryAlbumsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryArtistsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryMixScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryPlaylistsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibraryScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/library/LibrarySongsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/AutoPlaylistScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/LocalPlaylistScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/OnlinePlaylistScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/SpotifyLikedSongsScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/SpotifyPlaylistScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/TopPlaylistScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartScreen.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabEditorialSnapshot.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabSearchExploreHome.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchResult.kt",
"app/src/main/kotlin/com/metrolist/music/ui/screens/search/OnlineSearchScreen.kt",
"cloudflare/music-intelligence/test/musiclab-r41-section-memory.test.mjs",
"cloudflare/music-intelligence/test/turbo-r28-search-explore.test.mjs",
"tools/universal-apk-builder/prepare-turbo-r41-33121-env.py",
"tools/universal-apk-builder/verify-r41-33121-scope.py",
}
PROTECTED=[
"app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
"app/src/main/kotlin/com/metrolist/music/discogs/DiscogsModels.kt",
"app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt",
"app/src/main/kotlin/com/metrolist/music/ui/component/CoverRoundStorage.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
"app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
"app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
"app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
"app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt",
".motorlab/active-family.json",
"tools/universal-apk-builder/build-apk.sh",
]
def git(*a): return subprocess.check_output(["git",*a],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed==EXPECTED,"R41 unexpected files: "+repr(sorted(changed.symmetric_difference(EXPECTED)))
for p in PROTECTED:
    assert git("rev-parse",BASE+":"+p)==git("rev-parse","HEAD:"+p),"R41 protected file changed: "+p
comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
original=subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt"],text=True)
def load(s):return s.split("suspend fun loadPage(",1)[1].split("fun runSearch(",1)[0]
assert load(comp).replace("MusicLabCompilationSnapshot","UNCHANGED")==load(original), "STOP R39 broad Discogs search changed"
assert "query = criteria.query," in comp
assert "perPage = if (criteria.language == null) 100 else 25," in comp
assert "verifyContainingTrack(" not in comp
hit=Path("app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt").read_text()
oldhit=subprocess.check_output(["git","show",BASE+":app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"],text=True)
assert hit.split("internal fun MusicLabHistoricalHitParadeScreen",1)[0].split("internal object MusicLabHistoricalHitParade",1)[1]==oldhit.split("internal fun MusicLabHistoricalHitParadeScreen",1)[0].split("internal object MusicLabHistoricalHitParade",1)[1]
cover=Path("app/src/main/kotlin/com/metrolist/music/ui/component/DiscogsDirectVersionBrowser.kt").read_text()
assert "CoverRoundStorage.load(context, sessionKey)" in cover
assert 'MusicLabSectionMemory.text(context, "version:$sectionIdentity", "visit")' in cover
assert 'session.listOffset = offset' in cover
memory=Path("app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionMemory.kt").read_text()
assert "getSharedPreferences(PREFS, Context.MODE_PRIVATE)" in memory
assert "playNow(" not in memory and "playQueue(" not in memory
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R41 SCOPE PASS] screen memory only, R39 exact broad q search, unchanged audio, keys and Cover admission")
