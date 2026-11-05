#!/usr/bin/env python3
"""R69: remove only unresolved hint from Compilation, retaining R68 LAST_GOOD."""
import subprocess
from pathlib import Path

BASE="424f65670cc38f0aa0f56ce7b0ce6fc898dee7b2"
P="app/src/main/kotlin/com/metrolist/music/"
UI=P+"ui/component/CompilationScreen.kt"
EXPECTED={
    UI,
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationTrackSubtitleTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r68-compilation-clean-subtitle.test.mjs",
    "tools/universal-apk-builder/verify-r69-compilation-hint-scope.py",
    "tools/universal-apk-builder/prepare-turbo-r69-33149-env.py",
    ".github/workflows/musiclab-r69-compilation-hint-33149-apk.yml",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert changed==EXPECTED, "Unexpected/absent changes: "+repr(sorted(changed^EXPECTED))
previous=subprocess.check_output(["git","show",BASE+":"+UI],text=True)
updated=Path(UI).read_text()
before='    if (!resolved) return "$artist · Tocca per trovare e riprodurre"'
after='    if (!resolved) return artist'
assert previous.count(before)==1
assert updated==previous.replace(before,after), "R69 must change a single displayed string only"
assert 'CompilationResolvedTrack(track, it, "MusicLab FastPath", false)' in updated
assert 'CompilationResolvedTrack(track, song, "MusicLab FastPath", false)' in updated
for protected in (
    P+"playback/MusicService.kt",
    P+"playback/MusicLabArtworkAudioGate.kt",
    P+"playback/MusicLabSectionPlayback.kt",
    P+"ui/component/MusicLabFastPath.kt",
    P+"ui/component/CompilationSearchSessionPolicy.kt",
    P+"ui/component/CoverSearchScreen.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"discogs/DiscogsClient.kt",
    P+"discogs/CompilationTrackResolver.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse",BASE+":"+protected)==git("rev-parse","HEAD:"+protected), protected
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R69] EXACT one-line Compilation subtitle-only delta; player/search/prewarm untouched")
