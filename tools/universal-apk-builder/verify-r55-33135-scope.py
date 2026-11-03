#!/usr/bin/env python3
"""R55: exact source scope from installed R54; player/Cover/Hit Parade untouched."""
from pathlib import Path
import subprocess
BASE="49ec8a2f40a800c59c3c3903a53e65315afb8975"
P="app/src/main/kotlin/com/metrolist/music/"
EXPECTED={
    ".github/workflows/musiclab-r55-discogs-multiselect-progressive-audio-33135-apk.yml",
    P+"discogs/DiscogsSearchMode.kt",
    P+"discogs/DiscogsClient.kt",
    P+"ui/component/CompilationScreen.kt",
    "app/src/test/kotlin/com/metrolist/music/discogs/DiscogsSearchModeTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r51-compilation-album-modes.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r52-discogs-tracklist-verified.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r53-manual-title-player-restored.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r54-discogs-light-cover-three-modes.test.mjs",
    "cloudflare/music-intelligence/test/musiclab-r55-discogs-multiselect-progressive-audio.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r55-33135-env.py",
    "tools/universal-apk-builder/verify-r55-33135-scope.py",
}
def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()
subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],check=True)
actual=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
assert actual==EXPECTED,"R55 changed files inconsistent: "+repr(sorted(actual.symmetric_difference(EXPECTED)))
for untouched in (P+"playback/CoverPlaybackMemory.kt",P+"playback/PlayerConnection.kt",
    P+"playback/MusicService.kt",P+"playback/MusicLabSectionPlayback.kt",
    P+"ui/player/Player.kt",P+"ui/player/MiniPlayer.kt",
    P+"ui/component/CoverSearchScreen.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt"):
    assert git("rev-parse",BASE+":"+untouched)==git("rev-parse","HEAD:"+untouched),"Unexpected player/base mutation "+untouched
ui=Path(P+"ui/component/CompilationScreen.kt").read_text()
assert "DiscogsReleaseCategory.entries.forEach" in ui
assert "onVerified(candidate)" in ui
assert 'beginPlaybackPriorityBurst("compilation-track-tap")' in ui
assert "includeSingles = false" in ui
assert "selected = found.song.toMediaItem()" in ui
subprocess.run(["git","diff","--check",BASE,"HEAD"],check=True)
print("[R55] Scoped Discogs filters, progressive verified search and playback priority passed")
