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
        // LAB71: foundational musical identity and forbidden-recording gate
        // applies BEFORE any of the progressive confidence thresholds.
        if (!CoverLab71FoundationGate.matchesRequestedWork(
                candidate, targetTitle, originalArtist, videoTitle,
                allowExactTitleOnly = round >= 4,
            )) return Verdict(false, 1)
        val aliases = setOf(candidate.artist, originalArtist)
        val exactTitle = (
            TitleMeaningResolver.matchesBaseTitle(targetTitle, videoTitle, aliases) ||
                (round >= 4 && CoverLab76BroadTitle.matches(targetTitle, videoTitle, aliases))
        ) && (
            TitleMeaningResolver.matchesBaseTitle(targetTitle, candidate.title, aliases) ||
                (round >= 4 && CoverLab76BroadTitle.matches(targetTitle, candidate.title, aliases))
        )
        // No substring/prefix escape hatch: "Felicità puttana" is not
        // "Felicità", regardless of loose round or YouTube ID validity.
        val containsTitle = exactTitle
        val relation = candidate.workRelationConfirmed &&
            candidate.sources.any { it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true) }
        // LAB68: video title saying "Lucio Dalla" does NOT prove that
        // an unrelated upload channel represents Lucio Dalla. In the strict
        // first round require an artist/channel association or documentary work.
        val artistChannel = candidate.artist.trim()
        val originalPerformer = originalArtist.isNotBlank() &&
            (TitleMeaningResolver.sameArtist(artistChannel, originalArtist) ||
                artistChannel.contains(originalArtist, ignoreCase = true))
        val credibleOfficialChannel =
            artistChannel.contains("vevo", ignoreCase = true) ||
            artistChannel.contains("official", ignoreCase = true) ||
            artistChannel.contains(" - Topic", ignoreCase = true)
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
            (titleSignal && renditionMarker && officialMarker && credibleOfficialChannel) ||
            // LAB71: properly attributed real alternate-singer covers
            // belong in the BEST first round too, not only weaker tiers.
            (titleSignal && renditionMarker && candidate.artist.isNotBlank() &&
                !candidate.artist.equals("YouTube", ignoreCase = true))
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
