package com.metrolist.music.ui.component

/**
 * R65: lightweight, purely visual sorting for the already published Cover rows.
 * This never changes discovery, publication batches, audio sequence or saved rank.
 * Kotlin's stable sort keeps the published order for equal points or unknown years.
 */
internal object CoverVisibleSortPolicy {
    private fun dateKey(seed: DiscogsVersionSeed): Int? {
        // A raw video/upload year is not proof of a recording's release year.
        val year = CoverCardMetadata.publicationYear(seed)?.toIntOrNull()
            ?.takeIf { it in 1800..2100 } ?: return null
        val release = seed.releaseDate?.trim().orEmpty()
        val parts = release.split('-', '/', '.', limit = 3)
        val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: 0
        val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 } ?: 0
        return year * 10_000 + month * 100 + day
    }

    fun sort(rows: List<DiscogsVersionSeed>, mode: String): List<DiscogsVersionSeed> {
        if (rows.size < 2) return rows
        val comparator: Comparator<DiscogsVersionSeed> = when (mode) {
            "OLDEST" -> compareBy<DiscogsVersionSeed> { dateKey(it) == null }
                .thenBy { dateKey(it) ?: Int.MAX_VALUE }
            "NEWEST" -> compareBy<DiscogsVersionSeed> { dateKey(it) == null }
                .thenByDescending { dateKey(it) ?: Int.MIN_VALUE }
            else -> compareByDescending<DiscogsVersionSeed> {
                it.musiclabScore100 ?: (it.confidenceScore * 5).coerceIn(1, 100)
            }
        }
        return rows.sortedWith(comparator)
    }
}
