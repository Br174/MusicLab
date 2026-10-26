package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLab66DirectYouTubeTest {
    @Test fun manualCursorAdvancesTwoQueriesAtATime() {
        val q = CoverLab66DirectYouTube.queries("Sapore di sale", "Gino Paoli", 1)
        assertEquals(8, q.size)
        assertEquals(2, CoverLab66DirectYouTube.nextCursor(0, q.size))
        assertEquals(4, CoverLab66DirectYouTube.nextCursor(2, q.size))
        assertEquals(8, CoverLab66DirectYouTube.nextCursor(8, q.size))
        assertFalse(CoverLab66DirectYouTube.isExhausted(2, q.size, 5))
        assertTrue(CoverLab66DirectYouTube.isExhausted(8, q.size, 0))
        assertTrue(CoverLab66DirectYouTube.isExhausted(4, q.size, 50))
    }

    @Test fun originalAndOfficialTitlesAreHighestConfidence() {
        val signals = CoverLab66DirectYouTube.admissionSignals(
            "Sapore di sale", "Gino Paoli",
            "Gino Paoli - Sapore di sale Official Video",
            "Gino Paoli", 1, false,
        )
        assertEquals(3, signals)
        assertTrue(CoverLab66DirectYouTube.accepted(signals, 1))
        assertTrue(CoverLab66DirectYouTube.estimatedPrecision(signals, 1, false) >= 80)
    }

    @Test fun knownOriginalPerformerNeedsNoOfficialTextInYouTubeTitle() {
        val signals = CoverLab66DirectYouTube.admissionSignals(
            "Sapore di sale", "Gino Paoli",
            "Gino Paoli - Sapore di sale", "Gino Paoli", 1, false,
        )
        assertTrue(CoverLab66DirectYouTube.accepted(signals, 1))
        val officialWithoutAnchor = CoverLab66DirectYouTube.admissionSignals(
            "Sapore di sale", "",
            "Sapore di sale Official Video", "Artist Channel", 1, false,
        )
        assertTrue(CoverLab66DirectYouTube.accepted(officialWithoutAnchor, 1))
    }

    @Test fun noBlindSameTitleInFirstRoundButAllowedInFourth() {
        val signals = CoverLab66DirectYouTube.admissionSignals(
            "L'addio", "Giuni Russo", "L'addio", "Unrelated singer", 1, false,
        )
        assertEquals(1, signals)
        assertFalse(CoverLab66DirectYouTube.accepted(signals, 1))
        assertFalse(CoverLab66DirectYouTube.accepted(signals, 2))
        assertTrue(CoverLab66DirectYouTube.accepted(signals, 3))
        assertTrue(CoverLab66DirectYouTube.accepted(signals, 4))
        assertTrue(CoverLab66DirectYouTube.estimatedPrecision(signals, 4, false) < 40)
    }

    @Test fun documentedForeignWorkCanPassFirstRound() {
        val signals = CoverLab66DirectYouTube.admissionSignals(
            "Sapore di sale", "Gino Paoli",
            "Foreign title", "International Artist", 1, true,
        )
        assertTrue(CoverLab66DirectYouTube.accepted(signals, 1))
    }

    @Test fun roundsContainBroadQueriesWithoutOldQuotaPercentages() {
        assertTrue(CoverLab66DirectYouTube.queries("Soli", "Adriano Celentano", 1)
            .any { it.contains("foreign") })
        assertTrue(CoverLab66DirectYouTube.queries("Soli", "", 2)
            .any { it.contains("X Factor") })
        assertTrue(CoverLab66DirectYouTube.queries("Soli", "", 3)
            .any { it.contains("piano") })
        assertTrue(CoverLab66DirectYouTube.queries("Soli", "", 4)
            .any { it == "\"Soli\"" })
    }
}
