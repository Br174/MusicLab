#!/usr/bin/env python3
"""R49 UI-only approved delta against verified current R48 33128."""
from pathlib import Path
import subprocess

BASE="7a5f104021c6691db0ee2ea28f964b7d83bcc45f"
EXPECTED={
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHeaderTitle.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    "app/src/test/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHeaderPolicyTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r49-hitparade-header-date.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r49-33129-env.py",
    "tools/universal-apk-builder/verify-r49-33129-scope.py",
    ".github/workflows/musiclab-r49-hitparade-header-date-33129-apk.yml",
}
def git(*args): return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(git("diff","--name-only",BASE,"HEAD").splitlines())
assert changed == EXPECTED, "Outside R49 approved scope: "+repr(sorted(changed.symmetric_difference(EXPECTED)))

protected=(
    "app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/metrolist/music/playback/MusicLabSectionPlayback.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoResolver.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHitParadeVideoMenu.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastPath.kt",
    "app/src/main/kotlin/com/metrolist/music/ui/component/MusicLabFastHitArtwork.kt",
    ".motorlab/active-family.json",
    ".github/workflows/musiclab-r48-hitparade-video-recovery-33128-apk.yml",
)
for path in protected:
    if subprocess.run(["git","cat-file","-e",BASE+":"+path],capture_output=True).returncode == 0:
        assert git("rev-parse",BASE+":"+path)==git("rev-parse","HEAD:"+path),path

weekly="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalHitParade.kt"
extra="app/src/main/kotlin/com/metrolist/music/ui/screens/search/MusicLabHistoricalExtraCharts.kt"
for path in (weekly,extra):
    before=git("show",BASE+":"+path)
    after=Path(path).read_text()
    old='TopAppBar(title = { Text("Hit Parade Italiana") }, navigationIcon = {'
    assert before.count(old)==1
    assert after.count(old)==0
    assert after.replace(
        'TopAppBar(title = {\n            MusicLabHistoricalHeaderTitle('+(
          'selectedMode,\n                chart?.week?.millis ?: selected.timeInMillis'
          if path == weekly else 'mode,\n                chart?.actualDate ?: selectedMillis'
        )+'\n        }, navigationIcon = {',old) == before, "Unexpected edits to "+path

subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R49] Only header and tests updated; parser, playback, video choices, cover sources and signature preserved.")
