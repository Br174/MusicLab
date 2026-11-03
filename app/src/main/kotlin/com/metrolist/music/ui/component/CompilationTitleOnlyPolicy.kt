package com.metrolist.music.ui.component

import com.metrolist.music.discogs.CompilationTrackMatchPolicy
import com.metrolist.music.discogs.DiscogsTrack

/**
 * R52: the plain Cerca action MUST NOT rewrite text.
 * Only the explicit Titolo action removes the recognizable artist/video tail.
 */
internal object CompilationTitleOnlyPolicy {
    private val videoTail = Regex(
        """\s*(?:[-–—|]\s*)?(?:official(?:\s+music)?\s+video|official\s+audio|lyric(?:s)?\s+video|video\s+ufficiale|video\s+clip|4k|hd|hq)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val leadingArtist = Regex("""^(.{2,70}?)\s+[-–—|]\s+(.+)$""")
    private val editionTail = Regex(
        """^(?:live|remaster(?:ed)?|official video|official audio|hd|hq|4k|radio edit|album version)$""",
        RegexOption.IGNORE_CASE,
    )

    fun title(visibleText: String, knownArtist: String? = null): String {
        var value = CompilationFallbackQueryPolicy.songTitle(
            visibleText.trim(), knownArtist.orEmpty())
        if (knownArtist.isNullOrBlank()) {
            val pair = leadingArtist.matchEntire(value)
            // Standard visible MusicLab/YouTube "artist - title". Do not
            // throw away a real title when its suffix is just an edition tag.
            if (pair != null) {
                value = if (editionTail.matches(pair.groupValues[2].trim()))
                    pair.groupValues[1].trim()
                else pair.groupValues[2].trim()
            }
        }
        repeat(3) {
            value = videoTail.replace(value, "").trim()
            value = CompilationSearchTitlePolicy.clean(value)
        }
        return value.ifBlank { visibleText.trim() }
    }

    fun selectedTrackIndex(tracks: List<DiscogsTrack>,
                           title: String?, artist: String?): Int? {
        val wanted = title?.trim()?.takeIf(String::isNotBlank) ?: return null
        return tracks.indexOfFirst { track ->
            CompilationTrackMatchPolicy.matches(track, wanted, artist.orEmpty())
        }.takeIf { it >= 0 }
    }
}
