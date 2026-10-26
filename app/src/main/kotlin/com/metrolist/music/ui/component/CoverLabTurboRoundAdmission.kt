package com.metrolist.music.ui.component

/** Approved progressive MUSIC identity only. Technical YouTube verification is independent. */
internal object CoverLabTurboRoundAdmission {
    internal data class Verdict(val admitted: Boolean, val confidencePercent: Int)

    private val official = Regex("""\b(official|original|studio|vevo|remastered|remaster)\b""", RegexOption.IGNORE_CASE)
    private val rendition = Regex("""\b(cover|version|interpretation|acoustic|live|remix|tribute|duet|duetto|reprise|unplugged)\b""", RegexOption.IGNORE_CASE)

    fun evaluate(
        candidate: CoverSourceCandidate,
        targetTitle: String,
        originalArtist: String,
        round: Int,
        metadataTitle: String = candidate.playbackVideoTitle.orEmpty(),
    ): Verdict {
        val videoTitle = metadataTitle.ifBlank { candidate.title }
        val exactTitle = TitleMeaningResolver.matchesBaseTitle(targetTitle, videoTitle) ||
            TitleMeaningResolver.matchesBaseTitle(targetTitle, candidate.title)
        val containsTitle = videoTitle.contains(targetTitle.trim(), ignoreCase = true) ||
            candidate.title.contains(targetTitle.trim(), ignoreCase = true)
        val relation = candidate.workRelationConfirmed &&
            candidate.sources.any { it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true) }
        val originalPerformer = originalArtist.isNotBlank() &&
            (TitleMeaningResolver.sameArtist(candidate.artist, originalArtist) ||
                videoTitle.contains(originalArtist, ignoreCase = true))
        val officialMarker = official.containsMatchIn(videoTitle)
        val renditionMarker = rendition.containsMatchIn(videoTitle) ||
            candidate.sources.size >= 2
        val titleSignal = exactTitle || containsTitle
        // The first round prioritizes ORIGINAL official records and truly
        // documented foreign adaptations, not arbitrary title homonyms.
        // Title + confirmed original performer + verified YouTube metadata
        // must admit a genuine original even without the word "official".
        // If the reference artist is unknown, require a named channel and
        // an explicit official marker before admitting the first round.
        val namedOfficialWhenArtistUnknown = originalArtist.isBlank() &&
            candidate.artist.isNotBlank() &&
            !candidate.artist.equals("YouTube", ignoreCase = true) &&
            officialMarker
        val strong = relation || (titleSignal && originalPerformer) ||
            (titleSignal && namedOfficialWhenArtistUnknown) ||
            (titleSignal && renditionMarker && officialMarker)
        val medium = relation || (titleSignal && (originalPerformer || renditionMarker || officialMarker))
        val weak = relation || titleSignal
        val admissible = when {
            round <= 1 -> strong
            round == 2 -> medium
            round == 3 -> weak
            else -> containsTitle || exactTitle || relation
        }
        var score = 10
        if (titleSignal) score += 22
        if (exactTitle) score += 9
        if (relation) score += 37
        if (originalPerformer) score += 14
        if (officialMarker) score += 10
        if (renditionMarker) score += 7
        if (candidate.sources.distinct().size >= 2) score += 8
        // Estimated compatibility, never a statistical probability or guarantee.
        return Verdict(admissible, score.coerceIn(1, 100))
    }
}
