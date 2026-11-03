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

    /**
     * R53 - The artist is metadata from the locandina; NEVER alter visible
     * text until the explicit Titolo button is pressed. Known performer wins
     * over assumptions about "artist - title" versus "title - artist".
     */
    private fun comparable(value: String): String =
        value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    private fun matchesKnownArtist(part: String, artist: String): Boolean {
        val p = comparable(part)
        val a = comparable(artist)
        return a.isNotBlank() && (p == a || p.startsWith("$a ") ||
            a.startsWith("$p ") || p.contains(" $a "))
    }

    fun title(visibleText: String, knownArtist: String? = null): String {
        val original = visibleText.trim()
        if (original.isEmpty()) return original
        val artist = knownArtist.orEmpty().trim()
        var value = CompilationFallbackQueryPolicy.songTitle(original, artist)
        val separatorParts = leadingArtist.matchEntire(value)
        if (separatorParts != null) {
            val left = separatorParts.groupValues[1].trim()
            val right = separatorParts.groupValues[2].trim()
            value = when {
                editionTail.matches(right) -> left
                matchesKnownArtist(left, artist) -> right
                matchesKnownArtist(right, artist) -> left
                // For manually typed titles with no artist metadata, Discogs
                // video labels normally have the performer in front.
                artist.isBlank() -> right
                // A known artist may be credited in a longer group name.
                comparable(left).contains(comparable(artist)) -> right
                else -> value
            }
        } else if (artist.isNotBlank()) {
            // Some MusicLab poster labels have no dash at all:
            // "Gino Paoli Sapore di sale" or "Sapore di sale Gino Paoli".
            if (value.startsWith("$artist ", ignoreCase = true))
                value = value.substring(artist.length).trim()
            else if (value.endsWith(" $artist", ignoreCase = true))
                value = value.dropLast(artist.length).trim()
        }
        repeat(4) {
            value = videoTail.replace(value, "").trim()
            value = CompilationSearchTitlePolicy.clean(value)
        }
        return value.ifBlank { original }
    }

    fun selectedTrackIndex(tracks: List<DiscogsTrack>,
                           title: String?, artist: String?): Int? {
        val wanted = title?.trim()?.takeIf(String::isNotBlank) ?: return null
        return tracks.indexOfFirst { track ->
            CompilationTrackMatchPolicy.matches(track, wanted, artist.orEmpty())
        }.takeIf { it >= 0 }
    }
}
