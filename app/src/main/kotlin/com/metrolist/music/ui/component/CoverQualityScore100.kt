package com.metrolist.music.ui.component

/**
 * LAB46: display-quality points (0..100) for additional Cover rounds.
 * Not a calibrated probability, not a musical-work admission gate, and never
 * upgraded merely by pressing APPROVA. First 50 LAB45 scores remain /20.
 */
internal object CoverQualityScore100 {
    fun score(seed: DiscogsVersionSeed): Int {
        val sources = seed.sourceNames.filter(String::isNotBlank).distinct()
        val documentary = when {
            seed.workRelationConfirmed -> 35
            seed.credits.isNotEmpty() && sources.size >= 2 -> 27
            seed.credits.isNotEmpty() -> 21
            sources.size >= 2 -> 16
            else -> 8
        }
        val playback = when {
            seed.videoResolutionChecked && !seed.resolvedVideoId.isNullOrBlank() -> 25
            !seed.resolvedVideoId.isNullOrBlank() -> 14
            else -> 0
        }
        val metadata = when {
            seed.credits.isNotEmpty() && !seed.releaseDate.isNullOrBlank() -> 15
            seed.credits.isNotEmpty() || !seed.releaseDate.isNullOrBlank() -> 10
            seed.year != null || seed.releaseTitle.isNotBlank() -> 5
            else -> 0
        }
        val technical = when {
            !seed.resolvedVideoId.isNullOrBlank() && !seed.coverUrl.isNullOrBlank() -> 15
            !seed.resolvedVideoId.isNullOrBlank() || !seed.coverUrl.isNullOrBlank() -> 8
            else -> 0
        }
        val provenance = when {
            sources.size >= 3 -> 10
            sources.size == 2 -> 7
            sources.size == 1 -> 4
            else -> 0
        }
        return (documentary + playback + metadata + technical + provenance).coerceIn(0, 100)
    }
}
