package com.metrolist.music.discogs

/** One persisted mode owns all three independently selectable Discogs publication filters. */
internal enum class DiscogsReleaseCategory(val label: String) {
    COMPILATION("Compilation"), ALBUM("Album"), SINGLE("Singoli")
}

internal enum class DiscogsSearchMode(
    val label: String,
    val queryFormat: String?,
    val selected: Set<DiscogsReleaseCategory>,
) {
    COMPILATION("Compilation", "Compilation", setOf(DiscogsReleaseCategory.COMPILATION)),
    ALBUM("Album", "Album", setOf(DiscogsReleaseCategory.ALBUM)),
    BOTH("Compilation + Album", null, setOf(DiscogsReleaseCategory.COMPILATION, DiscogsReleaseCategory.ALBUM)),
    SINGLE("Singoli", "Single", setOf(DiscogsReleaseCategory.SINGLE)),
    COMPILATION_SINGLE("Compilation + Singoli", null, setOf(DiscogsReleaseCategory.COMPILATION, DiscogsReleaseCategory.SINGLE)),
    ALBUM_SINGLE("Album + Singoli", null, setOf(DiscogsReleaseCategory.ALBUM, DiscogsReleaseCategory.SINGLE)),
    ALL("Compilation + Album + Singoli", null, DiscogsReleaseCategory.entries.toSet());

    fun includes(category: DiscogsReleaseCategory): Boolean = category in selected

    /** Retain at least one checked format and preserve legacy BOTH in saved states. */
    fun toggle(category: DiscogsReleaseCategory): DiscogsSearchMode {
        val next = if (includes(category)) selected - category else selected + category
        return entries.firstOrNull { it.selected == next } ?: this
    }

    fun accepts(formats: List<String>): Boolean {
        val isAlbum = formats.any { it.equals("Album", ignoreCase = true) }
        val isCompilation = formats.any { it.equals("Compilation", ignoreCase = true) }
        val isSingle = formats.any { it.equals("Single", ignoreCase = true) }
        return when (this) {
            COMPILATION -> isCompilation
            ALBUM -> isAlbum && !isCompilation
            BOTH -> isAlbum || isCompilation
            SINGLE -> isSingle
            COMPILATION_SINGLE -> isCompilation || isSingle
            ALBUM_SINGLE -> (isAlbum && !isCompilation) || isSingle
            ALL -> isAlbum || isCompilation || isSingle
        }
    }

    companion object {
        fun restored(value: String?): DiscogsSearchMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: COMPILATION
    }
}
