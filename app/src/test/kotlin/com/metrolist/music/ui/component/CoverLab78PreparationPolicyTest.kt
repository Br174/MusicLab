package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab78PreparationPolicyTest {
    private fun row(id: String) = DiscogsVersionSeed(
        trackTitle = "Il mondo", artist = "Singer", releaseTitle = "Album", releaseId = 0,
        masterId = null, year = null, releaseDate = null, kind = DiscogsVersionKind.STUDIO,
        country = null, formats = emptyList(), formatDescriptions = emptyList(), labels = emptyList(),
        coverUrl = null, durationSeconds = 210, fingerprint = id, resolvedVideoId = id,
    )

    @Test fun hiddenNextRoundCannotRefreshTheVisibleRound() {
        assertFalse(CoverPreparationPolicy.mayRefreshVisible(2, 1))
        assertTrue(CoverPreparationPolicy.mayRefreshVisible(2, 2))
    }

    @Test fun coverInfoCompletionDoesNotHideAnActiveSourceNetworkFailure() {
        val diagnostics = listOf(CoverSourceDiagnostic("Apple/iTunes", false, 0, "rete/non disponibile"))
        assertFalse(CoverPreparationPolicy.documentaryComplete(true, diagnostics))
        assertTrue(CoverPreparationPolicy.documentaryComplete(true,
            listOf(CoverSourceDiagnostic("Apple/iTunes", true, 0, "nessun risultato"))))
        assertTrue(CoverPreparationPolicy.documentaryComplete(true,
            listOf(CoverSourceDiagnostic("SecondHandSongs", false, 0, "Escluso dal recupero approfondito"))))
    }

    @Test fun manualSelectionReservesAlreadyPreparedVideoIds() {
        val rows = CoverPreparationPolicy.reservedRows(listOf(row("shown000001")), listOf(row("hidden00001")))
        assertEquals(setOf("shown000001", "hidden00001"), rows.map { it.resolvedVideoId }.toSet())
    }

    @Test fun replacementPreservesItsVisibleRoundAndPosition() {
        val current = row("old00000001").copy(coverRound = 3, roundPosition = 127)
        val replacement = CoverPreparationPolicy.replacementInSlot(current, row("new00000001"))
        assertEquals("new00000001", replacement.resolvedVideoId)
        assertEquals(3, replacement.coverRound)
        assertEquals(127, replacement.roundPosition)
    }

    @Test fun firstFiveMetadataCanShareARequestWithoutChangingOrder() {
        val ordered = (1..8).map { "video-$it" }
        assertEquals(listOf("video-1", "video-3", "video-4", "video-5", "video-6"),
            CoverPreparationPolicy.metadataIds("video-1", ordered, setOf("video-2"), protectPlayback = false))
        // One bounded request checks three IDs during normal playback.
        // The audio governor still pauses work when playback is at risk.
        assertEquals(listOf("video-1", "video-2", "video-3"),
            CoverPreparationPolicy.metadataIds("video-1", ordered, emptySet(), protectPlayback = true))
    }
}
