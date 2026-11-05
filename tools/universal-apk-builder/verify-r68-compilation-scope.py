#!/usr/bin/env python3
"""R68 strict visual-only diff gate, based on signed R67 LAST_GOOD."""
import subprocess
from pathlib import Path

BASE = "990663ccef4e01e27b8061d6f5a704de30bba587"
P = "app/src/main/kotlin/com/metrolist/music/"
COMP = P + "ui/component/CompilationScreen.kt"
EXPECTED = {
    COMP,
    "app/src/test/kotlin/com/metrolist/music/ui/component/CompilationTrackSubtitleTest.kt",
    "cloudflare/music-intelligence/test/musiclab-r68-compilation-clean-subtitle.test.mjs",
    "tools/universal-apk-builder/prepare-turbo-r68-33148-env.py",
    "tools/universal-apk-builder/verify-r68-compilation-scope.py",
    ".github/workflows/musiclab-r68-compilation-clean-subtitle-33148-apk.yml",
}

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
actual = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
assert actual == EXPECTED, "Unexpected or missing changed files: " + repr(sorted(actual ^ EXPECTED))

original = subprocess.check_output(["git", "show", BASE+":"+COMP], text=True)
current = Path(COMP).read_text()
anchor = 'private data class CompilationTokenState(val loaded: Boolean = false, val token: String = "")\n\n'
helper = '''private data class CompilationTokenState(val loaded: Boolean = false, val token: String = "")

/**
 * R68: display formatting ONLY. Preserve MusicLab FastPath as the internal
 * provenance for cache/selection/diagnostics; never print it as a subtitle.
 * No metadata, stream lookup or playback scheduling is changed.
 */
internal fun compilationTrackSubtitle(
    artist: String,
    source: String?,
    resolved: Boolean,
): String {
    if (!resolved) return "$artist · Tocca per trovare e riprodurre"
    val label = source?.trim().orEmpty()
    return if (label.isBlank() || label.equals("MusicLab FastPath", ignoreCase = true)) {
        artist
    } else "$artist · $label"
}

'''
old_ui = '''                    val artist = track.artists.joinToString(", ").ifBlank { "Artista non indicato" }
                    val source = resolved?.source ?: "Tocca per trovare e riprodurre"
                    Text(
                        "$artist · $source",'''
new_ui = '''                    val artist = track.artists.joinToString(", ").ifBlank { "Artista non indicato" }
                    Text(
                        compilationTrackSubtitle(artist, resolved?.source, resolved != null),'''
assert original.count(anchor) == 1 and original.count(old_ui) == 1
expected_source = original.replace(anchor, helper).replace(old_ui, new_ui)
assert current == expected_source, "Compilation logic was changed beyond exactly the approved UI-only subtitle adjustment"

# Core search, caching, Media3, history and playback remain byte-identical.
for path in (
    P+"playback/MusicLabSectionPlayback.kt",
    P+"playback/MusicService.kt",
    P+"playback/MusicLabArtworkAudioGate.kt",
    P+"playback/PlaybackSprintPolicy.kt",
    P+"playback/MusicLabSwipeInputPolicy.kt",
    P+"ui/component/MusicLabFastPath.kt",
    P+"ui/component/CoverSearchScreen.kt",
    P+"ui/screens/search/MusicLabHistoricalHitParade.kt",
    P+"ui/screens/search/MusicLabHistoricalExtraCharts.kt",
    P+"ui/screens/search/MusicLabLightArtwork.kt",
    P+"ui/screens/search/MusicLabChartArtwork.kt",
    P+"ui/screens/search/MusicLabHitParadeDiscArtwork.kt",
    P+"discogs/DiscogsClient.kt",
    ".motorlab/MOTORLAB_SYNC_STATE.txt",
):
    assert git("rev-parse", BASE+":"+path) == git("rev-parse", "HEAD:"+path), "Protected: "+path

assert 'CompilationResolvedTrack(track, it, "MusicLab FastPath", false)' in current
assert 'CompilationResolvedTrack(track, song, "MusicLab FastPath", false)' in current
assert 'MusicLabFastPath.findArtwork(' in current
assert 'UAB_UPDATE_FAMILY_VERSION_CODE' in Path(
    "tools/universal-apk-builder/prepare-turbo-r68-33148-env.py"
).read_text()
subprocess.run(["git", "diff", "--check", BASE, "HEAD"], check=True)
print("[R68] Exact visual-only Compilation diff, internal FastPath and player unchanged")
