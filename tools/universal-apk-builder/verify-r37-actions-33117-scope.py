#!/usr/bin/env python3
"""R37 isolated LAB and exact-source preservation gate, based on verified R36."""
from pathlib import Path
import subprocess

BASE="f4d5cc500a6fe94103306f627acb1fa4084b79b8"
EXPECTED={
 "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/DiscogsClient.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicy.kt",
 "app/src/main/res/drawable/compilation_music.xml",
 "app/src/test/kotlin/com/metrolist/music/discogs/CompilationTrackMatchPolicyTest.kt",
 "tools/universal-apk-builder/prepare-turbo-r37-actions-33117-env.py",
 "tools/universal-apk-builder/verify-r37-actions-33117-scope.py",
 ".github/workflows/musiclab-r37-player-actions-compilation-33117-apk.yml",
}
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(subprocess.check_output(["git","diff","--name-only",BASE,"HEAD"],text=True).splitlines())
assert changed==EXPECTED,"STOP: R37 changed unexpected paths: "+repr(sorted(changed.symmetric_difference(EXPECTED)))

PROTECTED=[
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabChartArtwork.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPathPolicy.kt",
 "app/src/main/kotlin/com/metrolist/music/discogs/CompilationTrackResolver.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
 "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt",
 "app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt",
 ".motorlab/active-family.json",
 "tools/universal-apk-builder/build-apk.sh",
]
for path in PROTECTED:
    before=subprocess.check_output(["git","rev-parse",BASE+":"+path],text=True).strip()
    after=subprocess.check_output(["git","rev-parse","HEAD:"+path],text=True).strip()
    assert before==after,"STOP: protected feature modified: "+path

menu=Path("app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt").read_text()
player=Path("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt").read_text()
comp=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationScreen.kt").read_text()
bridge=Path("app/src/main/kotlin/com/metrolist/music/ui/component/CompilationNavigationBridge.kt").read_text()
assert menu.index('text = "Crediti"')<menu.index('text = "Artista"')<menu.index('text = "Album"')<menu.index('text = "Cover"')<menu.index('text = "Compilation"')
assert menu.index('text = "Compilation"')<menu.index('title = { Text("Copia link") }')
assert not 'title = { Text("Vai all\'artista") }' in menu
assert not 'title = { Text("Vai all\'album") }' in menu
assert not 'stringResource(R.string.view_artist)' in player
assert 'if (isPodcast && mediaMetadata.album != null)' in player
assert 'NewActionGrid(' in menu and 'columns = 3' in menu and 'columns = 2' in menu
assert 'CompilationNavigationBridge.openForTrack(' in menu
assert 'trackTitle = criteria.trackTitle' in comp and 'verifyContainingTrack(' in comp
assert 'CompilationTrackMatchPolicy.matches(' in comp
assert 'fun openForTrack(' in bridge and 'fun takePendingTrack()' in bridge
assert 'MusicLabFastPath.find(' in comp
assert 'CompilationTrackResolver.resolveTrack(track, current.videos)' in comp
assert 'MusicLabFastPathPolicy.mayContinueCompilation' in comp
assert Path("app/src/main/res/drawable/compilation_music.xml").exists()
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[MUSICLAB R37 PASS] Isolated menu + track match, historical Hit Parade/Cover/audio/Scudo retained.")
