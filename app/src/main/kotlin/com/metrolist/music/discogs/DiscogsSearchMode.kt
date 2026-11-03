package com.metrolist.music.discogs

/**
 * R51: native Discogs release-format distinction. 'Both' reads the same
 * release index without narrowing the API to Compilation or Album.
 * A release can legitimately carry both format descriptions.
 */
internal enum class DiscogsSearchMode(val label: String, val queryFormat: String?) {
    COMPILATION("Compilation", "Compilation"),
    ALBUM("Album", "Album"),
    BOTH("Entrambi", null);

    fun accepts(formats: List<String>): Boolean {
        val isAlbum = formats.any { it.equals("Album", ignoreCase = true) }
        val isCompilation = formats.any { it.equals("Compilation", ignoreCase = true) }
        return when (this) {
            COMPILATION -> isCompilation
            ALBUM -> isAlbum && !isCompilation
            BOTH -> isAlbum || isCompilation
        }
    }

    companion object {
        fun restored(value: String?): DiscogsSearchMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: COMPILATION
    }
}
