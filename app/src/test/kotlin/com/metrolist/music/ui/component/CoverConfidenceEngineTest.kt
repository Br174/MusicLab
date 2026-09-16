package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverConfidenceEngineTest {
    @Test
    fun whoSampledRelationshipIsConfirmedEvenWithTranslatedTitle() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                whoSampledRelationship = true,
                translatedOrAdaptedTitle = true,
                titleSimilarity = 0.05,
            )
        )

        assertEquals(CoverConfidence.CONFIRMED, result)
    }

    @Test
    fun workIdentifierMatchIsConfirmed() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(workIdentifierMatch = true)
        )

        assertEquals(CoverConfidence.CONFIRMED, result)
    }

    @Test
    fun webSourcePlusMatchingWritersIsVerified() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                matchingWritersOrComposers = true,
                webSourceConfirmations = 1,
                translatedOrAdaptedTitle = true,
            )
        )

        assertEquals(CoverConfidence.VERIFIED, result)
    }

    @Test
    fun webAndAiNeedAnotherConcreteSignal() {
        val verified = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                webSourceConfirmations = 1,
                aiSupportsSameWork = true,
                translatedOrAdaptedTitle = true,
            )
        )
        val rejected = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                webSourceConfirmations = 0,
                aiSupportsSameWork = true,
                translatedOrAdaptedTitle = true,
            )
        )

        assertEquals(CoverConfidence.VERIFIED, verified)
        assertEquals(CoverConfidence.REJECTED, rejected)
    }

    @Test
    fun closeTitleAndDurationIsOnlyProbable() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                titleSimilarity = 0.97,
                durationSimilarity = 0.80,
            )
        )

        assertEquals(CoverConfidence.PROBABLE, result)
    }

    @Test
    fun sameTitleWithImplausibleDurationIsRejected() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                titleSimilarity = 1.0,
                durationSimilarity = 0.05,
            )
        )

        assertEquals(CoverConfidence.REJECTED, result)
    }

    @Test
    fun karaokeOrOtherDisallowedVariantIsAlwaysRejected() {
        val result = CoverConfidenceEngine.evaluate(
            CoverEvidence(
                whoSampledRelationship = true,
                disallowedVariant = true,
            )
        )

        assertEquals(CoverConfidence.REJECTED, result)
    }
}
