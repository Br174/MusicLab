package com.metrolist.music.ui.component

/** Display metadata must describe the recording, never its upload or a related work. */
internal object CoverCardMetadata {
    private val yearPrefix = Regex("""^(\d{4})(?:-\d{2}(?:-\d{2})?)?$""")
    private val studioTag = Regex("""(?i)\b(studio version|studio recording|studio session|in studio|registrazione in studio)\b""")
    private val publicationSources = setOf("cover.info", "discogs", "musicbrainz", "apple/itunes", "spotify")
    private val sourceOrder = listOf("COVER.INFO", "Discogs", "MusicBrainz", "Apple/iTunes", "Spotify", "YouTube Music", "YouTube")

    fun publicationYear(seed: DiscogsVersionSeed): String? {
        if (!CoverPriorityOrder.isPublished(seed) &&
            seed.sourceNames.none { it.lowercase(java.util.Locale.ROOT) in publicationSources }) return null
        val dated = seed.releaseDate?.trim()?.let { yearPrefix.matchEntire(it)?.groupValues?.get(1) }?.toIntOrNull()
        return (dated ?: seed.year)?.takeIf { it in 1000..2999 }?.toString()
    }

    fun sources(seed: DiscogsVersionSeed): List<String> = seed.sourceNames
        .map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .sortedBy { source -> sourceOrder.indexOfFirst { it.equals(source, true) }.let { if (it < 0) Int.MAX_VALUE else it } }

    fun playbackService(seed: DiscogsVersionSeed): String? = when {
        seed.resolvedVideoId.isNullOrBlank() -> null
        seed.resolvedVideoSource.equals("YouTube Music", true) -> "YouTube Music"
        else -> "YouTube"
    }

    fun isStudio(seed: DiscogsVersionSeed): Boolean =
        seed.kind == DiscogsVersionKind.STUDIO && CoverPriorityOrder.isPublished(seed) &&
            studioTag.containsMatchIn(listOf(seed.trackTitle, seed.releaseTitle,
                seed.resolvedVideoTitle.orEmpty()).plus(seed.formatDescriptions).joinToString(" "))
}
