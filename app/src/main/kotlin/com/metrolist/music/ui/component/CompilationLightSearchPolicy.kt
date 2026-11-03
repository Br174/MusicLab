package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsTrack
import java.text.Normalizer
import java.util.Locale

/** Three lightweight Discogs-tracklist modes. This does not use the MusicLab Cover engine. */
internal enum class CompilationLookupMode(val label: String) {
    TITLE_ARTIST("Titolo + cantante"),
    TITLE("Solo titolo"),
    COVER_LITE("Cover");

    companion object {
        fun restored(raw: String?): CompilationLookupMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TITLE_ARTIST
    }
}

/**
 * R54: unverified, lightweight discovery only. A cover/alternate remix can
 * appear without credits; we never claim it is a verified cover.
 *
 * Never inspect the title of the release/compilation to establish a match.
 * Only individual DiscogsTrack entries count.
 */
internal object CompilationLightSearchPolicy {
    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim().replace(Regex("""\s+"""), " ")

    private val editionMarker = Regex(
        """(?i)\b(remix|mash[\s-]?up|dj|mix|re[\s-]?edit|edit|bootleg|extended|club|dance|live|acoustic|instrumental|radio\s+edit|version|rework|rework(?:ed)?|cover|remaster(?:ed)?|alternate|alternative)\b"""
    )

    private fun isVersionOf(trackTitle: String, wantedTitle: String): Boolean {
        val wanted = norm(wantedTitle)
        val actual = norm(trackTitle)
        if (wanted.isBlank() || actual.isBlank()) return false
        if (actual == wanted) return true
        // "Brutta storia" is NOT a version of "Brutta".
        if (!actual.startsWith("$wanted ")) return false
        val suffix = actual.removePrefix("$wanted ").trim()
        // Brackets alone do not prove a version. Reject unrelated lyric
        // phrases such as "Brutta (Sei davvero brutta)".
        return editionMarker.containsMatchIn(suffix)
    }

    private fun originalArtistPresent(track: DiscogsTrack, originalArtist: String): Boolean {
        val singer = norm(originalArtist)
        if (singer.isBlank()) return false
        val names = track.artists.map(::norm).filter(String::isNotBlank)
        if (names.any { it == singer || it.startsWith("$singer ") || singer.startsWith("$it ") })
            return true
        // An alternate label may include the actual original performer in its title.
        val title = norm(track.title)
        return title == singer || title.startsWith("$singer ") ||
            title.endsWith(" $singer") || title.contains(" $singer ")
    }

    fun accepts(
        track: DiscogsTrack,
        songTitle: String,
        originalArtist: String,
        lookup: CompilationLookupMode,
    ): Boolean {
        if (!isVersionOf(track.title, songTitle)) return false
        return when (lookup) {
            TITLE -> true
            TITLE_ARTIST -> {
                val singer = norm(originalArtist)
                if (singer.isBlank()) true
                else {
                    val attributed = track.artists.map(::norm).filter(String::isNotBlank)
                        .filterNot { it == "various" || it == "various artists" }
                    attributed.isEmpty() || originalArtistPresent(track, originalArtist)
                }
            }
            COVER_LITE -> {
                if (originalArtistPresent(track, originalArtist)) false
                else {
                    val named = track.artists.map(::norm).filter {
                        it.isNotBlank() && it != "various" && it != "various artists" &&
                            it != "unknown" && it != "uncredited"
                    }
                    // Another performer OR an explicitly named alternative
                    // version. A plain, uncredited canonical song isn't cover.
                    named.isNotEmpty() || editionMarker.containsMatchIn(track.title)
                }
            }
        }
    }

    fun indexTitle(inputTitle: String, artist: String): String =
        CompilationTitleOnlyPolicy.title(inputTitle, artist).trim()
}
