package com.metrolist.music.ui.component

/** R2: evidence orders candidates; only the explicit content exceptions veto them. */
internal object CoverLabTurboRoundAdmission {
    internal data class Verdict(val admitted: Boolean, val confidencePercent: Int)

    fun evaluate(
        candidate: CoverSourceCandidate,
        targetTitle: String,
        originalArtist: String,
        round: Int,
        metadataTitle: String = candidate.playbackVideoTitle.orEmpty(),
    ): Verdict {
        val videoTitle = metadataTitle.ifBlank { candidate.title }
        if (CoverLab71FoundationGate.isForbidden(
                candidate.title, videoTitle, candidate.album.orEmpty(),
                candidate.sourceUrl.orEmpty(),
            )) return Verdict(false, 1)
        val aliases = setOf(candidate.artist, originalArtist).filter(String::isNotBlank).toSet()
        val requested = TitleMeaningResolver.matchesBaseTitle(targetTitle, videoTitle, aliases) &&
            TitleMeaningResolver.matchesBaseTitle(targetTitle, candidate.title, aliases)
        val recordedTitle = TitleMeaningResolver.matchesBaseTitle(candidate.title, videoTitle, aliases)
        val relation = candidate.workRelationConfirmed && candidate.sources.any {
            it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true)
        }
        val original = candidate.originalWorkReference ||
            (originalArtist.isNotBlank() &&
                CoverLab71FoundationGate.isOriginalPerformer(candidate.artist, originalArtist))
        val catalogue = candidate.sources.any {
            (it.equals("Discogs", true) && candidate.releaseVerified) || it.equals("Apple/iTunes", true) ||
                it.equals("Spotify", true) || (it.equals("YouTube Music", true) && candidate.releaseVerified)
        }
        val named = candidate.artist.isNotBlank() && !candidate.artist.equals("YouTube", true)
        // This is an explained evidence index, never a calibrated probability.
        val estimate = when {
            relation && recordedTitle -> 95
            original && requested -> 92
            catalogue && requested && !candidate.album.isNullOrBlank() -> 85
            requested && named -> 65
            requested -> 45
            relation && !recordedTitle -> 20
            TitleMeaningResolver.qualityScore(targetTitle, videoTitle, aliases) > 0 -> 25
            else -> 5
        }
        return Verdict(true, estimate)
    }
}
