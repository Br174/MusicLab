package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab83OriginalResolutionTest {
    private val proposal = CoverOriginalResolver.Proposal("Il mondo", "Primo interprete", "https://musicbrainz.org/work/work-id")
    @Test fun oldestYearAndComposerAreNotFirstPublicationProof() {
        assertFalse(CoverOriginalResolver.documentsFirstPublication("Il mondo, composer Primo interprete, oldest recording 1930", proposal))
        assertFalse(CoverOriginalResolver.documentsFirstPublication("Il mondo first recorded by Primo interprete in 1930", proposal))
    }
    @Test fun oneExplicitFirstPublicationDocumentCanBeEnough() {
        assertTrue(CoverOriginalResolver.documentsFirstPublication("Il mondo was first released by Primo interprete in 1960.", proposal))
    }
    @Test fun titleOrPerformerOfAnotherWorkIsNotProof() {
        assertFalse(CoverOriginalResolver.documentsFirstPublication("Un'altra opera was first released by Primo interprete.", proposal))
        assertFalse(CoverOriginalResolver.documentsFirstPublication("Il mondo was first released by Altro interprete.", proposal))
    }
    @Test fun configuredAiProposalAloneDoesNotConfirmAnOriginal() {
        val parsed = CoverOriginalResolver.parseProposal("{\"title\":\"Il mondo\",\"artist\":\"Primo interprete\",\"document\":\"https://musicbrainz.org/work/id\"}")
        assertNotNull(parsed)
        val candidate = CoverSourceCandidate(parsed!!.title, parsed.artist, listOf("MusicBrainz"),
            workRelationConfirmed = true, originalWorkReference = true)
        assertNull(CoverPriorityOrder.originalReference(listOf(candidate)))
        assertNotNull(CoverPriorityOrder.originalReference(listOf(candidate.copy(firstPublicationConfirmed = true))))
    }
    @Test fun modelCannotRequestPrivateOrArbitraryDocumentEndpoints() {
        for (url in listOf("http://musicbrainz.org/work/id", "https://localhost/a", "https://127.0.0.1/a", "https://musicbrainz.org.attacker.test/a", "https://user@musicbrainz.org/a"))
            assertFalse(url, CoverOriginalResolver.safeDocument(url))
        assertTrue(CoverOriginalResolver.safeDocument("https://www.discogs.com/release/1"))
    }
    @Test fun aNegatedFirstPublicationClaimCannotConfirmAnOriginal() {
        assertFalse(CoverOriginalResolver.documentsFirstPublication(
            "Il mondo was not first released by Primo interprete in 1960.", proposal))
    }

}
