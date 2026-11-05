package com.metrolist.music.ui.component

import java.text.Normalizer
import java.util.Locale

/**
 * R66: a matching/search key is NOT a title to present to the listener.
 *
 * Keep Unicode text intact (NFC). Only use accent folding for equality.
 * A video uploader's unaccented spelling cannot erase a better documentary
 * title, and an unrelated foreign adaptation is never renamed.
 */
internal object CoverTitleTypography {
    private val marks = Regex("\\p{M}+")
    private val asciiAccentMark = Regex("(?<=\\p{L})['’](?=\\s|$|[\\)\\]\\}])")

    fun display(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

    fun folded(value: String): String =
        Normalizer.normalize(display(value), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(asciiAccentMark, "")
            .lowercase(Locale.ROOT)

    fun hasDiacritics(value: String): Boolean =
        display(value) != Normalizer.normalize(display(value), Normalizer.Form.NFD)
            .replace(marks, "")

    private fun diacriticCount(value: String): Int =
        Normalizer.normalize(display(value), Normalizer.Form.NFD)
            .count { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    /** Used when TWO sources describe exactly the same work/version title. */
    fun prefer(first: String, second: String): String {
        val a = display(first)
        val b = display(second)
        if (a.isBlank()) return b
        if (b.isBlank() || folded(a) != folded(b)) return a
        return if (diacriticCount(b) > diacriticCount(a)) b else a
    }

    /**
     * UI-only correction: reapply a trusted work's accent to a video's exact
     * leading title. Keep "(Live)", remixes and other video qualifiers intact.
     * No substring/lexical continuation may become a different song.
     */
    fun shown(videoTitle: String, referenceTitle: String): String {
        val video = display(videoTitle)
        val original = display(referenceTitle)
        if (video.isBlank() || original.isBlank() || !hasDiacritics(original)) return video
        if (folded(video) == folded(original)) return prefer(video, original)
        if (video.length < original.length) return video

        val candidatePrefix = video.substring(0, original.length)
        if (folded(candidatePrefix) != folded(original)) return video
        val suffix = video.substring(original.length)
        if (suffix.isEmpty()) return prefer(video, original)
        // Only an explicit decoration boundary, never "Perché" -> "Perché che...".
        val decorated = suffix.startsWith(" (") || suffix.startsWith(" [") ||
            suffix.startsWith(" - ") || suffix.startsWith(" – ") ||
            suffix.startsWith(" — ") || suffix.startsWith(" | ")
        return if (decorated) prefer(candidatePrefix, original) + suffix else video
    }

    /** Search-only fallback, never used as a displayed title. */
    fun unaccentedQuery(title: String): String? =
        display(title).takeIf(::hasDiacritics)?.let(::folded)
}
