package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit
import org.junit.Assert.*
import org.junit.Test

class CoverR9EvidenceRecoveryTest {
    private val credits = listOf(DiscogsCredit("Carlo Pes", "Composed By"), DiscogsCredit("Gianni Meccia", "Written By"))
    private fun candidate(artist: String = "Interprete") = CoverSourceCandidate(
        "Il mondo", artist, listOf("Discogs"), playbackVideoId = "video000001",
        playbackVideoTitle = "Il mondo", credits = credits, releaseVerified = true)
    private fun seed() = DiscogsVersionSeed("Il mondo", "Interprete", "Album", 1, null, 1968, "1968",
        DiscogsVersionKind.STUDIO, null, emptyList(), emptyList(), emptyList(), null, 170, "recording",
        resolvedVideoId = "video000001", resolvedVideoTitle = "Il mondo", sourceNames = listOf("YouTube"))

    @Test fun newlyAcquiredWriterCreditsSurviveMetadataRefresh() {
        val enriched = CoverPriorityOrder.refreshEvidence(seed(), candidate(), "Il mondo", "Jimmy Fontana", 1)
        assertEquals(credits, enriched.credits)
    }
    @Test fun karaokeOnlyInTheChannelNameIsStillExcluded() {
        val c = candidate("Karaoke Italia").copy(sources = listOf("COVER.INFO"), workRelationConfirmed = true)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(c, "Il mondo", "Jimmy Fontana", 6).rejected)
    }
    @Test fun amateurWithDocumentedWorkStaysInItsCompatibleCategory() {
        val c = candidate().copy(sources = listOf("MusicBrainz"), workRelationConfirmed = true,
            releaseVerified = false, playbackVideoTitle = "Interprete - Il mondo home studio cover")
        assertFalse(CoverLabTurboRoundAdmission.evaluate(c, "Il mondo", "Jimmy Fontana", 1).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(c, "Il mondo", "Jimmy Fontana", 3).admitted)
    }
    @Test fun foreignDocumentaryLeadPrecedesAnUnverifiedCatalogueSummary() {
        val weak = candidate().copy(credits = emptyList(), releaseVerified = false,
            playbackVideoId = null, sources = listOf("MusicBrainz"))
        val foreign = candidate("Foreign singer").copy(title = "My World", playbackVideoId = null,
            playbackVideoTitle = null, category = AiCoverCategory.FOREIGN, sources = listOf("COVER.INFO"),
            workRelationConfirmed = true, releaseVerified = false)
        assertEquals(foreign, CoverPriorityOrder.order(listOf(weak, foreign), "Il mondo", "Jimmy Fontana", 1).first())
    }
    @Test fun creditsActuallyAvailableAlreadyPermitTheFirstRound() {
        assertTrue(CoverLabTurboRoundAdmission.evaluate(candidate(), "Il mondo", "Jimmy Fontana", 1,
            referenceCredits = credits).admitted)
    }
    @Test fun documentedCreditVariationMatchesWithoutAddingAnExtraVote() {
        val reference = listOf(DiscogsCredit("Carlo Pes", "Composer", aliases = listOf("C. Pes")),
            DiscogsCredit("Gianni Meccia", "Writer"))
        val c = candidate().copy(credits = listOf(DiscogsCredit("C. Pes", "Composed By"),
            DiscogsCredit("Gianni Meccia", "Written By")))
        val v = CoverLabTurboRoundAdmission.evaluate(c, "Il mondo", "Jimmy Fontana", 1, referenceCredits = reference)
        assertTrue(v.admitted)
        assertEquals(3, v.evidence.size)
    }
}
