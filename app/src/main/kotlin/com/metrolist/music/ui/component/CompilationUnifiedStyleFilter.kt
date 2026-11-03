package com.metrolist.music.ui.component

/**
 * R51: one visible Stile dropdown contains BOTH historical Discogs genres
 * and detailed styles. Preserve which API parameter the selected value uses.
 * Selecting any option removes the opposite/hidden filter.
 */
internal object CompilationUnifiedStyleFilter {
    private const val GENRE = "Genere · "
    private const val STYLE = "Stile · "

    fun options(genres: List<String>, styles: List<String>): List<String> =
        listOf("Tutto") +
            genres.filterNot { it == "Tutto" }.distinct().map { GENRE + it } +
            styles.filterNot { it == "Tutto" }.distinct().map { STYLE + it }

    fun parse(option: String): Pair<String?, String?> = when {
        option.startsWith(GENRE) -> option.removePrefix(GENRE) to null
        option.startsWith(STYLE) -> null to option.removePrefix(STYLE)
        else -> null to null
    }

    fun label(genre: String?, style: String?): String = when {
        genre != null && style != null -> GENRE + genre + " + " + STYLE + style
        genre != null -> GENRE + genre
        style != null -> STYLE + style
        else -> "Tutto"
    }
}
