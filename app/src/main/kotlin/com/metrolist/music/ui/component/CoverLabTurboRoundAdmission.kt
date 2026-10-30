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
        "TV", "X Factor", "The Voice", "Amici", "Sanremo", "Eurovision", "talent show", "audition",
        "home studio", "bedroom", "street performance", "amateur", "local band")
    fun matchesWorkTitle(target: String, value: String, aliases: Set<String> = emptySet()): Boolean {
        if (target.isNotBlank() && target.trim().equals(value.trim(), ignoreCase = true)) return true
        if (TitleMeaningResolver.matchesBaseTitle(target, value, aliases)) return true
        val base = TitleMeaningResolver.canonical(target)
        if (base.isBlank()) return false
        // Descriptor aliases must never strip an actual word from the requested work.
        val decorations = trackTags.filterTo(linkedSetOf()) {
            val tag = TitleMeaningResolver.canonical(it)
            tag != base && !base.startsWith("$tag ") && !base.endsWith(" $tag")
        }
        return TitleMeaningResolver.matchesBaseTitle(target, value, aliases + decorations)
    }

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
            fun isWriter(credit: com.metrolist.music.discogs.DiscogsCredit): Boolean {
                val role = credit.role.lowercase(java.util.Locale.ROOT)
                return listOf("writ", "compos", "lyric", "music by", "words by").any(role::contains)
            }
            fun names(credit: com.metrolist.music.discogs.DiscogsCredit): Set<String> =
                (listOf(credit.name) + credit.aliases).map(TitleMeaningResolver::canonical)
                    .filter { it.isNotBlank() && it !in setOf("unknown", "anonymous", "anonimo", "various", "traditional") }.toSet()
            val candidateWriters = candidate.credits.filter(::isWriter).map(::names)
            val counted = linkedSetOf<String>()
            var matchedWorkWriter = false
            referenceCredits.filter(::isWriter).forEach { credit ->
                val identity = names(credit)
                if (identity.isNotEmpty() && identity.none { it in counted } &&
                    candidateWriters.any { it.intersect(identity).isNotEmpty() }) {
                    add("writer:${TitleMeaningResolver.canonical(credit.name)}")
                    counted.addAll(identity)
                    matchedWorkWriter = true
                }
            }
            // A matching writer documents the WORK. An independently
            // authenticated track listing credits the particular PERFORMER;
            // those are distinct factual observations. Merely having an
            // album, uploader title, publication date or fame is NOT proof.
            val authenticCatalogueTrack = candidate.sources.any { source ->
                when {
                    source.equals("Apple/iTunes", true) ->
                        candidate.sourceUrl?.startsWith("https://music.apple.com/") == true &&
                            (candidate.sourceUrl?.contains("/song/") == true ||
                                candidate.sourceUrl?.contains("?i=") == true)
                    source.equals("Spotify", true) ->
                        candidate.sourceUrl?.startsWith("https://open.spotify.com/track/") == true
                    source.equals("Discogs", true) ->
                        candidate.releaseVerified && candidate.recordingReleaseId != null &&
                            !candidate.recordingTrackPosition.isNullOrBlank()
                    else -> false
                }
            }
            if (matchedWorkWriter && authenticCatalogueTrack &&
                candidate.artist.isNotBlank() &&
                !candidate.artist.equals("YouTube", true)) {
                add("catalogue-credited-performance")
            }
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

    private fun categoryRound(candidate: CoverSourceCandidate, video: String): Int {
        if (candidate.releaseVerified || candidate.category == AiCoverCategory.FOREIGN && candidate.workRelationConfirmed) return 1
        val context = candidate.title + " " + video
        return when {
            Regex("(?i)\\b(tv|sanremo|eurovision|x factor|the voice|amici|talent|mashup|remix|ai cover)\\b").containsMatchIn(context) -> 2
            Regex("(?i)\\b(home studio|bedroom|amateur|street performance|local band|cover band|tribute band)\\b").containsMatchIn(context) -> 3
            Regex("(?i)\\b(guitar|chitarra|piano|instrumental|strumentale|orchestra|choir|jazz|soul|folk)\\b").containsMatchIn(context) -> 4
            else -> 1
        }
    }

    fun candidate(row: DiscogsVersionSeed): CoverSourceCandidate = CoverSourceCandidate(
        title = row.trackTitle, artist = row.artist, sources = row.sourceNames,
        year = row.year, album = row.releaseTitle, credits = row.credits,
        coverUrl = row.coverUrl, language = row.language, durationSeconds = row.durationSeconds,
        recordingReleaseId = row.releaseId.takeIf { it > 0 }, recordingMasterId = row.masterId,
        recordingTrackPosition = row.track?.position, collectorHave = row.collectorHave,
        evidenceScore = row.confidenceScore,
        playbackVideoId = row.resolvedVideoId, playbackVideoTitle = row.resolvedVideoTitle,
        playbackVideoSource = row.resolvedVideoSource, sourceUrl = row.sourceUrl,
        workRelationConfirmed = row.workRelationConfirmed,
        originalWorkReference = row.originalWorkReference, releaseVerified = row.discogsVerificationChecked,
        category = when {
            row.kind == DiscogsVersionKind.LIVE -> AiCoverCategory.LIVE
            row.kind == DiscogsVersionKind.REMIX -> AiCoverCategory.REMIX
            row.workRelationConfirmed -> AiCoverCategory.FOREIGN
            else -> AiCoverCategory.COVER
        },
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
                candidate.title, candidate.artist, videoTitle, candidate.album.orEmpty(),
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
        val minimum = maxOf(minimumRound(evidence), categoryRound(candidate, videoTitle))
        return Verdict(evidence.isNotEmpty() && round >= minimum, estimate, evidence, minimum,
            rejected = evidence.isEmpty())
    }
}
