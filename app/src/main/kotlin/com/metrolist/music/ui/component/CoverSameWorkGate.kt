package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit

/**
 * LAB40: an equal song title NEVER establishes that two recordings represent
 * the same musical work. Keep weak hits in the discovery pool for future
 * documentary verification, but do not publish them as proven Cover results.
 *
 * This is local, deterministic and makes no extra network requests.
 */
internal object CoverSameWorkGate {
    private val documentarySources = setOf(
        "cover.info", "secondhandsongs", "whosampled", "musicbrainz",
    )

    internal fun hasDocumentaryRelation(seed: DiscogsVersionSeed): Boolean =
        seed.workRelationConfirmed &&
            seed.sourceNames.any { it.trim().lowercase() in documentarySources }

    internal fun hasWorkIdentity(
        seed: DiscogsVersionSeed,
        referenceArtist: String,
        originalCredits: List<DiscogsCredit>,
    ): Boolean {
        if (seed.manuallyApproved) return true
        if (referenceArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(seed.artist, referenceArtist)
        ) return true
        // The archive's own numerical score and a video URL are not proof.
        if (hasDocumentaryRelation(seed)) return true
        return DiscogsVersionSource.sharedWorkCreditNames(seed, originalCredits).isNotEmpty()
    }

    internal fun capUnverifiedScore(
        seed: DiscogsVersionSeed,
        referenceArtist: String,
        originalCredits: List<DiscogsCredit>,
    ): DiscogsVersionSeed {
        if (hasWorkIdentity(seed, referenceArtist, originalCredits)) return seed
        return seed.copy(
            confidenceScore = seed.confidenceScore.coerceAtMost(3),
            confidenceReasons = (seed.confidenceReasons +
                "Identità dell’opera non verificata: un titolo identico non dimostra una cover"
            ).distinct(),
        )
    }
}
