package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSameWorkGateTest {
    private fun seed(artist: String, source: String = "Discogs", linked: Boolean = false) =
        DiscogsVersionSeed(
            trackTitle = "Titolo comune",
            artist = artist,
            releaseTitle = "Album",
            releaseId = 1,
            masterId = null,
            year = 1982,
            releaseDate = "1982",
            kind = DiscogsVersionKind.STUDIO,
            country = "Italy",
            formats = emptyList(),
            formatDescriptions = emptyList(),
            labels = emptyList(),
            coverUrl = null,
            durationSeconds = 220,
            fingerprint = artist,
            sourceNames = listOf(source),
            workRelationConfirmed = linked,
            confidenceScore = 20,
        )

    @Test fun homonymousSongsStaySeparate() {
        val wrong = seed("Artista B")
        assertFalse(CoverSameWorkGate.hasWorkIdentity(wrong, "Artista A", emptyList()))
        assertEquals(3, CoverSameWorkGate.capUnverifiedScore(
            wrong, "Artista A", emptyList()
        ).confidenceScore)
    }

    @Test fun sameSingerIsAccepted() {
        assertTrue(CoverSameWorkGate.hasWorkIdentity(seed("Artista A"), "Artista A", emptyList()))
    }

    @Test fun documentaryRelationIsAccepted() {
        assertTrue(CoverSameWorkGate.hasWorkIdentity(
            seed("Artista B", "COVER.INFO", linked = true), "Artista A", emptyList()
        ))
    }

    @Test fun numericCloudScoreIsNotProof() {
        assertFalse(CoverSameWorkGate.hasWorkIdentity(
            seed("Artista B", "Archivio Cloud", linked = true), "Artista A", emptyList()
        ))
    }
}
