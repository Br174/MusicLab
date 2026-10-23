package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDeepRecoveryGateTest {
    private fun seed(source: String, documented: Boolean) = DiscogsVersionSeed(
        trackTitle = "Che cosa c'è",
        artist = "Cantante ospite",
        releaseTitle = "Versione",
        releaseId = 0,
        masterId = null,
        year = 2025,
        releaseDate = "2025",
        kind = DiscogsVersionKind.STUDIO,
        country = null,
        formats = emptyList(),
        formatDescriptions = emptyList(),
        labels = emptyList(),
        coverUrl = null,
        durationSeconds = null,
        fingerprint = source,
        sourceNames = listOf(source),
        workRelationConfirmed = documented,
    )

    @Test fun titleAndYoutubeVideoWithoutWorkProofAreRejected() {
        assertFalse(CoverSameWorkGate.hasDocumentaryRelation(
            seed("YouTube discovery round 2", false)))
    }

    @Test fun cloudScoreCannotClaimWorkIdentity() {
        assertFalse(CoverSameWorkGate.hasDocumentaryRelation(
            seed("Archivio Cloud", true)))
    }

    @Test fun directlyLinkedMusicBrainzWorkIsProof() {
        assertTrue(CoverSameWorkGate.hasDocumentaryRelation(
            seed("MusicBrainz", true)))
    }

    @Test fun directlyLinkedSecondHandSongsWorkIsProof() {
        assertTrue(CoverSameWorkGate.hasDocumentaryRelation(
            seed("SecondHandSongs", true)))
    }

    @Test fun sourceNameWithoutWorkRelationIsNotProof() {
        assertFalse(CoverSameWorkGate.hasDocumentaryRelation(
            seed("MusicBrainz", false)))
    }

    @Test fun sourceNameCaseVariationsAreAllowedWhenDocumented() {
        assertTrue(CoverSameWorkGate.hasDocumentaryRelation(
            seed("COVER.INFO", true)))
    }
}
