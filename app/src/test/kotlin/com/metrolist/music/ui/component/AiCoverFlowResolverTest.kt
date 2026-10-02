package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoverFlowResolverTest {
    @Test
    fun `same-language cover title tolerates typography but rejects another Italian title`() {
        assertTrue(
            AiCoverFlowResolver.sameItalianTitle(
                "Cosa resterà degli anni 80",
                "COSA RESTERA' DEGLI ANNI 80",
            ),
        )
        assertTrue(AiCoverFlowResolver.sameItalianTitle("Morire qui", "Morire qui"))
        assertFalse(AiCoverFlowResolver.sameItalianTitle("Il mondo", "Il mondo che vorrei"))
        assertFalse(
            AiCoverFlowResolver.sameItalianTitle(
                "Cosa resterà degli anni 80",
                "Cosa rimane degli anni 80",
            ),
        )
    }
    @Test
    fun `same-language candidate stays uncertain without evidence and passes with one strong evidence`() {
        val original = AiCoverOriginalInfo(
            title = "Il mondo",
            artist = "Jimmy Fontana",
            language = "it",
        )
        val noEvidence = AiCoverCandidate(
            title = "Il mondo",
            artist = "Altro Artista",
            category = AiCoverCategory.COVER,
            language = "it",
        )
        val uncertain = AiCoverFlowResolver.applyEvidencePolicy(
            originalTitle = original.title,
            originalInfo = original,
            candidates = listOf(noEvidence),
        ).single()
        assertTrue(uncertain.brainStatus == AiBrainDecisionStatus.UNCERTAIN)

        val strong = noEvidence.copy(
            brainSignals = listOf(
                AiBrainSignal(
                    kind = "composer_match",
                    strength = "strong",
                    direction = "positive",
                ),
            ),
        )
        val accepted = AiCoverFlowResolver.applyEvidencePolicy(
            originalTitle = original.title,
            originalInfo = original,
            candidates = listOf(strong),
        ).single()
        assertTrue(accepted.brainStatus == AiBrainDecisionStatus.PROBABLE)
    }

    @Test
    fun `foreign adaptation may use a different title with one strong same-work evidence`() {
        val original = AiCoverOriginalInfo(
            title = "Il mondo",
            artist = "Jimmy Fontana",
            language = "it",
        )
        val foreign = AiCoverCandidate(
            title = "The World",
            artist = "Foreign Artist",
            category = AiCoverCategory.FOREIGN,
            language = "en",
            sameWorkScore = 82,
        )
        val accepted = AiCoverFlowResolver.applyEvidencePolicy(
            originalTitle = original.title,
            originalInfo = original,
            candidates = listOf(foreign),
        ).single()
        assertTrue(accepted.brainStatus == AiBrainDecisionStatus.PROBABLE)
    }

    @Test
    fun `same-language lexical continuation is rejected as another title`() {
        val original = AiCoverOriginalInfo(
            title = "Il mondo",
            artist = "Jimmy Fontana",
            language = "it",
        )
        val wrong = AiCoverCandidate(
            title = "Il mondo che vorrei",
            artist = "Altro Artista",
            category = AiCoverCategory.COVER,
            language = "it",
            sameWorkScore = 90,
        )
        val rejected = AiCoverFlowResolver.applyEvidencePolicy(
            originalTitle = original.title,
            originalInfo = original,
            candidates = listOf(wrong),
        ).single()
        assertTrue(rejected.brainStatus == AiBrainDecisionStatus.REJECTED)
    }

}
