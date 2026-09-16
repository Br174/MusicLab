/**
 * MusicLab cover confidence engine
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

/**
 * Confidence assigned to a candidate alternate performance.
 *
 * CONFIRMED is reserved for strong work-level evidence such as an explicit
 * WhoSampled cover relationship or a shared musical-work identifier.
 * VERIFIED requires multiple independent signals.
 * PROBABLE is useful for discovery/ranking but must not be presented as certain.
 * REJECTED should not be shown as a normal cover result.
 */
internal enum class CoverConfidence {
    CONFIRMED,
    VERIFIED,
    PROBABLE,
    REJECTED,
}

/**
 * Evidence collected by the different MusicLab cover sources.
 *
 * AI support is deliberately only a corroborating signal: by itself it can
 * never produce CONFIRMED or VERIFIED. This keeps a generated answer from
 * becoming the sole proof that two recordings are the same musical work.
 */
internal data class CoverEvidence(
    val whoSampledRelationship: Boolean = false,
    val workIdentifierMatch: Boolean = false,
    val matchingWritersOrComposers: Boolean = false,
    val webSourceConfirmations: Int = 0,
    val aiSupportsSameWork: Boolean = false,
    val titleSimilarity: Double = 0.0,
    val durationSimilarity: Double? = null,
    val translatedOrAdaptedTitle: Boolean = false,
    val differentArtist: Boolean = false,
    val explicitVariantLabel: Boolean = false,
    val disallowedVariant: Boolean = false,
)

internal object CoverConfidenceEngine {
    fun evaluate(evidence: CoverEvidence): CoverConfidence {
        if (evidence.disallowedVariant) return CoverConfidence.REJECTED

        // Strong structured relationships identify the musical work directly.
        if (evidence.whoSampledRelationship || evidence.workIdentifierMatch) {
            return CoverConfidence.CONFIRMED
        }

        val title = evidence.titleSimilarity.coerceIn(0.0, 1.0)
        val duration = evidence.durationSimilarity?.coerceIn(0.0, 1.0)
        val webConfirmations = evidence.webSourceConfirmations.coerceAtLeast(0)

        // Two independent web sources are enough for VERIFIED, even when an
        // adaptation has a completely different translated title.
        if (webConfirmations >= 2) {
            return CoverConfidence.VERIFIED
        }

        // Matching writers/composers is a strong work-level signal, but require
        // one more independent clue before calling the result verified.
        if (
            evidence.matchingWritersOrComposers &&
            (webConfirmations >= 1 || evidence.aiSupportsSameWork || title >= 0.72)
        ) {
            return CoverConfidence.VERIFIED
        }

        // Google/web + AI can corroborate each other, but only when there is an
        // additional concrete clue: title affinity, plausible duration, or an
        // explicitly identified translated/adapted title.
        if (
            webConfirmations >= 1 &&
            evidence.aiSupportsSameWork &&
            (
                title >= 0.55 ||
                    (duration != null && duration >= 0.45) ||
                    evidence.translatedOrAdaptedTitle
                )
        ) {
            return CoverConfidence.VERIFIED
        }

        // A clearly incompatible duration is a hard warning when no structured
        // source has already established the work relationship.
        if (duration != null && duration < 0.20) {
            return CoverConfidence.REJECTED
        }

        // Very close title + plausible duration is useful for discovery, but is
        // deliberately only PROBABLE.
        if (
            title >= 0.92 &&
            (duration == null || duration >= 0.45)
        ) {
            return CoverConfidence.PROBABLE
        }

        // MusicLab's broader discovery may find titles that differ slightly due
        // to subtitles, translations or uploader naming. Keep them as PROBABLE
        // only when another concrete clue exists: a different performer or an
        // explicit cover/live/acoustic/version label, plus plausible duration.
        if (
            title >= 0.70 &&
            duration != null &&
            duration >= 0.45 &&
            (evidence.differentArtist || evidence.explicitVariantLabel)
        ) {
            return CoverConfidence.PROBABLE
        }

        // AI alone, a translated title alone, or a merely similar title are not
        // sufficient proof of identity.
        return CoverConfidence.REJECTED
    }
}
