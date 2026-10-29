package com.metrolist.music.ui.component

/** Distinct work corroborations allocate rounds. Display percentages are cosmetic. */
internal object CoverLabTurboRoundAdmission {
    internal data class Verdict(
        val admitted: Boolean, val confidencePercent: Int,
        val evidence: Set<String> = emptySet(), val minimumRound: Int = 6,
        val rejected: Boolean = false,
    )

    // Musical track/version descriptors, never lexical extensions of the work title.
    private val trackTags = setOf("instrumental", "strumentale", "instrumentale", "performed", "guitar",
        "chitarra", "piano", "backing track", "tutorial", "base musicale",
        "mashup", "mash up", "AI", "intelligenza artificiale", "a cappella", "acappella",
        "jazz", "soul", "folk", "orchestra", "orchestral", "choir", "classical", "alternative rock",
        "pop", "blues", "metal", "reggae", "bossa nova", "solo", "electric", "cover band", "tribute band",
        "TV", "X Factor", "The Voice", "Amici", "Sanremo", "Eurovision", "talent show", "audition")
    fun matchesWorkTitle(target: String, value: String, aliases: Set<String> = emptySet()): Boolean =
        TitleMeaningResolver.matchesBaseTitle(target, value, aliases + trackTags)

    fun facts(candidate: CoverSourceCandidate, targetTitle: String, referenceArtist: String,
        metadataTitle: String = candidate.playbackVideoTitle.orEmpty(),
        referenceCredits: List<com.metrolist.music.discogs.DiscogsCredit> = emptyList()): Set<String> {
        val video = metadataTitle.ifBlank { candidate.title }
        val aliases = setOf(candidate.artist, referenceArtist).filter(String::isNotBlank).toSet()
        val recorded = matchesWorkTitle(candidate.title, video, aliases)
        val exact = recorded && matchesWorkTitle(targetTitle, candidate.title, aliases)
        val documented = candidate.workRelationConfirmed && candidate.sources.any {
            it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true)
        }
        val adaptation = recorded && documented && candidate.category == AiCoverCategory.FOREIGN
        if (!exact && !adaptation) return emptySet()
        return buildSet {
            add("title")
            // A documented work relation and its credited rendition are distinct facts,
            // even when both come from a single authoritative catalogue.
            if (documented) {
                add("documented-work")
                if (candidate.artist.isNotBlank() && !candidate.artist.equals("YouTube", true)) add("credited-rendition")
            }
            fun writers(credits: List<com.metrolist.music.discogs.DiscogsCredit>) = credits.filter {
                val role = it.role.lowercase(java.util.Locale.ROOT)
                listOf("writ", "compos", "lyric", "music by", "words by").any(role::contains)
            }.map { TitleMeaningResolver.canonical(it.name) }.filter(String::isNotBlank).toSet()
            val sharedWriters = writers(candidate.credits).intersect(writers(referenceCredits))
            sharedWriters.forEach { add("writer:$it") }
            val reference = TitleMeaningResolver.canonical(referenceArtist)
            val text = " " + TitleMeaningResolver.canonical(video) + " "
            if (reference.isNotBlank() &&
                (CoverLab71FoundationGate.isOriginalPerformer(candidate.artist, referenceArtist) ||
                    text.contains(" $reference "))) add("reference-performer")
        }
    }

    fun minimumRound(evidence: Set<String>): Int = when {
        evidence.size >= 3 -> 1
        evidence.size >= 2 -> 2
        else -> 6
    }

    fun candidate(row: DiscogsVersionSeed): CoverSourceCandidate = CoverSourceCandidate(
        title = row.trackTitle, artist = row.artist, sources = row.sourceNames,
        year = row.year, album = row.releaseTitle, credits = row.credits,
        playbackVideoId = row.resolvedVideoId, playbackVideoTitle = row.resolvedVideoTitle,
        playbackVideoSource = row.resolvedVideoSource, sourceUrl = row.sourceUrl,
        workRelationConfirmed = row.workRelationConfirmed,
        originalWorkReference = row.originalWorkReference, releaseVerified = row.discogsVerificationChecked,
        category = if (row.workRelationConfirmed) AiCoverCategory.FOREIGN else AiCoverCategory.COVER,
    )

    fun evaluate(
        candidate: CoverSourceCandidate,
        targetTitle: String,
        originalArtist: String,
        round: Int,
        metadataTitle: String = candidate.playbackVideoTitle.orEmpty(),
        referenceCredits: List<com.metrolist.music.discogs.DiscogsCredit> = emptyList(),
    ): Verdict {
        val videoTitle = metadataTitle.ifBlank { candidate.title }
        if (CoverLab71FoundationGate.isForbidden(
                candidate.title, videoTitle, candidate.album.orEmpty(),
                candidate.sourceUrl.orEmpty(),
            )) return Verdict(false, 1, rejected = true)
        val aliases = setOf(candidate.artist, originalArtist).filter(String::isNotBlank).toSet()
        val requested = matchesWorkTitle(targetTitle, videoTitle, aliases) &&
            matchesWorkTitle(targetTitle, candidate.title, aliases)
        val recordedTitle = matchesWorkTitle(candidate.title, videoTitle, aliases)
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
        val evidence = facts(candidate, targetTitle, originalArtist, metadataTitle, referenceCredits)
        val minimum = minimumRound(evidence)
        return Verdict(evidence.isNotEmpty() && round >= minimum, estimate, evidence, minimum,
            rejected = evidence.isEmpty())
    }
}
