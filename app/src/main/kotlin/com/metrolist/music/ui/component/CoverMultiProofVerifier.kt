package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit

/**
 * LAB52: proof strategies for *one musical work*, not video/title confidence.
 *
 * DIRECT_WORK: an explicit recording-to-work or documented derivative relation.
 * CREDITED_COMPOSITION: matching named writers/composers on the original Work
 * and a cataloged candidate with a compatible work title.
 * CATALOG_CONVERGENCE: independent catalogs agree AND writer evidence matches.
 *
 * YouTube, AI, Cloud snapshots, upload date and title similarity are NEVER
 * sufficient proof on their own. This helper is non-networked and playback-safe.
 */
internal object CoverMultiProofVerifier {
    internal enum class Method {
        DIRECT_WORK,
        CREDITED_COMPOSITION,
        CATALOG_CONVERGENCE,
        TRANSLATED_WORK,
    }

    internal data class Verdict(
        val certified: Boolean,
        val methods: Set<Method>,
        val keepForRetry: Boolean,
        val sharedAuthors: Set<String> = emptySet(),
    )

    private val catalogSources = setOf(
        "discogs", "apple/itunes", "spotify", "musicbrainz", "cover.info",
    )
    private val weakSources = setOf(
        "youtube/video", "ai scout", "archivio cloud", "musiclab interno",
        "secondhandsongs",
    )

    internal fun verify(
        seed: DiscogsVersionSeed,
        workTitle: String,
        originalArtist: String,
        originalCredits: List<DiscogsCredit>,
    ): Verdict {
        if (seed.artist.isBlank() || seed.trackTitle.isBlank()) {
            return Verdict(false, emptySet(), false)
        }
        if (originalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(seed.artist, originalArtist)
        ) return Verdict(false, emptySet(), false)

        val distinctSources = seed.sourceNames.map { it.trim().lowercase() }
            .filter(String::isNotBlank).distinct().toSet()
        val titleRelation = TitleMeaningResolver.classify(
            targetTitle = workTitle,
            value = seed.trackTitle,
            artistAliases = setOf(originalArtist, seed.artist).filter(String::isNotBlank).toSet(),
        )
        val direct = CoverSameWorkGate.hasDocumentaryRelation(seed)
        val shared = DiscogsVersionSource.sharedWorkCreditNames(seed, originalCredits)
            .filter { author ->
                author.length >= 4 &&
                    author !in setOf("unknown", "various", "traditional", "anonimo", "anonymous")
            }.toSet()
        // Original song's Work author + candidate's catalog composer credit
        // constitute two different, auditable pieces of composition evidence.
        val credited = titleRelation != TitleMeaningMatch.DIFFERENT &&
            shared.isNotEmpty() && distinctSources.any { it in catalogSources } &&
            distinctSources.any { it !in weakSources }

        // Two corroborating catalogs alone do not prove a cover: the original
        // Work's actual credited writers must also match the candidate.
        val independentCatalogs = distinctSources.count { it in catalogSources } >= 2
        val methods = buildSet {
            if (direct) add(Method.DIRECT_WORK)
            if (credited) add(Method.CREDITED_COMPOSITION)
            if (credited && independentCatalogs) add(Method.CATALOG_CONVERGENCE)
            if (direct && titleRelation == TitleMeaningMatch.DIFFERENT) {
                add(Method.TRANSLATED_WORK)
            }
        }
        return Verdict(
            certified = direct || credited,
            methods = methods,
            keepForRetry = !direct && !credited && distinctSources.isNotEmpty(),
            sharedAuthors = shared,
        )
    }

    /**
     * Source callbacks can arrive hours apart. Accumulate provenance rather
     * than allowing the first catalog's weak row to erase a later strong match.
     * Category stays in the recording identity: live and studio remain distinct.
     */
    internal fun accumulate(
        previous: List<CoverSourceCandidate>,
        incoming: List<CoverSourceCandidate>,
    ): List<CoverSourceCandidate> {
        val merged = linkedMapOf<String, CoverSourceCandidate>()
        (previous + incoming).forEach { candidate ->
            val key = listOf(
                TitleMeaningResolver.canonical(candidate.title),
                TitleMeaningResolver.canonical(candidate.artist).removePrefix("the "),
                candidate.category.name,
            ).joinToString("|")
            val earlier = merged[key]
            merged[key] = if (earlier == null) candidate else earlier.copy(
                sources = (earlier.sources + candidate.sources).distinct(),
                credits = (earlier.credits + candidate.credits).distinctBy {
                    TitleMeaningResolver.canonical(it.name) + "|" +
                        TitleMeaningResolver.canonical(it.role)
                },
                year = earlier.year ?: candidate.year,
                album = earlier.album ?: candidate.album,
                sourceUrl = earlier.sourceUrl ?: candidate.sourceUrl,
                coverUrl = earlier.coverUrl ?: candidate.coverUrl,
                playbackVideoId = earlier.playbackVideoId ?: candidate.playbackVideoId,
                playbackVideoTitle = earlier.playbackVideoTitle ?: candidate.playbackVideoTitle,
                playbackVideoSource = earlier.playbackVideoSource ?: candidate.playbackVideoSource,
                workRelationConfirmed =
                    earlier.workRelationConfirmed || candidate.workRelationConfirmed,
                originalWorkReference =
                    earlier.originalWorkReference || candidate.originalWorkReference,
                evidenceScore = maxOf(earlier.evidenceScore, candidate.evidenceScore),
            )
        }
        return merged.values.toList()
    }
}
