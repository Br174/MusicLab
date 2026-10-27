package com.metrolist.music.ui.component

/**
 * LAB74 only ADDS positive prominence to already retrieved candidates.
 *
 * Important: a score is NEVER an admission condition. Missing release
 * metadata does not reject or postpone a YouTube video, nor does a known
 * album claim by itself prove that the uploaded video is that recording.
 * Every existing LAB73 identity, anti-karaoke and duplicate gate stays put.
 */
internal object CoverLab74PositivePriority {
    private val studioMarkers = Regex(
        """(?i)\b(studio recording|studio version|official audio|album track|""" +
            """original recording|official music video|remastered|studio session)\b""",
    )
    private val professionallyRecorded = Regex(
        """(?i)\b(x factor|the voice|sanremo|television|rai|official performance)\b""",
    )

    /**
     * Album/EP/single is a positive signal, not an exclusive requirement.
     * Discogs + an actual named release ranks above a vague "official" video.
     * COVER.INFO foreign same-Work relations are promoted immediately.
     */
    internal fun score(candidate: CoverSourceCandidate, originalArtist: String): Int {
        val sources = candidate.sources.map { it.trim().lowercase() }.toSet()
        val publishedHint = !candidate.album.isNullOrBlank()
        var points = 0
        if ("cover.info" in sources && candidate.workRelationConfirmed) points += 85
        if ("discogs" in sources && publishedHint) points += 75
        if (publishedHint && ("apple/itunes" in sources || "spotify" in sources)) points += 65
        if (candidate.workRelationConfirmed && "musicbrainz" in sources) points += 40
        if (originalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(candidate.artist, originalArtist)) points += 45
        if (publishedHint) points += 15
        if (studioMarkers.containsMatchIn(candidate.playbackVideoTitle.orEmpty())) points += 15
        if (professionallyRecorded.containsMatchIn(candidate.playbackVideoTitle.orEmpty())) points += 6
        if (sources.size >= 2) points += 12
        return points
    }

    internal fun order(
        candidates: List<CoverSourceCandidate>, originalArtist: String
    ): List<CoverSourceCandidate> = candidates.withIndex()
        .sortedWith(compareByDescending<IndexedValue<CoverSourceCandidate>> {
            score(it.value, originalArtist)
        }.thenBy { it.index })
        .map { it.value }

    /**
     * Early YouTube rows are ordered for relevance, not stopped. All
     * original SongItem objects remain present in their full input set.
     */
    internal fun videoScore(
        title: String, creditedArtist: String, originalArtist: String
    ): Int {
        var score = 0
        if (studioMarkers.containsMatchIn(title)) score += 24
        if (originalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(creditedArtist, originalArtist)) score += 20
        if (professionallyRecorded.containsMatchIn(title)) score += 8
        return score
    }
}
